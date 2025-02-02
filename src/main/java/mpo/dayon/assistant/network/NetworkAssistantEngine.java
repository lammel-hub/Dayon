package mpo.dayon.assistant.network;

import com.dosse.upnp.UPnP;
import mpo.dayon.assisted.compressor.CompressorEngineConfiguration;
import mpo.dayon.common.capture.CaptureEngineConfiguration;
import mpo.dayon.common.concurrent.RunnableEx;
import mpo.dayon.common.configuration.ReConfigurable;
import mpo.dayon.common.event.Listeners;
import mpo.dayon.common.log.Log;
import mpo.dayon.common.network.NetworkEngine;
import mpo.dayon.common.network.message.*;
import mpo.dayon.common.security.CustomTrustManager;
import mpo.dayon.common.version.Version;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.awt.*;
import java.awt.datatransfer.ClipboardOwner;
import java.io.*;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;

import static java.lang.String.format;
import static mpo.dayon.common.utils.SystemUtilities.safeClose;
import static mpo.dayon.common.version.Version.isCompatibleVersion;

public class NetworkAssistantEngine extends NetworkEngine implements ReConfigurable<NetworkAssistantEngineConfiguration> {

    private final NetworkCaptureMessageHandler captureMessageHandler;

    private final NetworkMouseLocationMessageHandler mouseMessageHandler;

    private final ClipboardOwner clipboardOwner;

    private final Listeners<NetworkAssistantEngineListener> listeners = new Listeners<>();

    private NetworkAssistantEngineConfiguration configuration;

    private SSLServerSocketFactory ssf;

    private static final String APP_NAME = "Dayon!";

    public NetworkAssistantEngine(NetworkCaptureMessageHandler captureMessageHandler, NetworkMouseLocationMessageHandler mouseMessageHandler, ClipboardOwner clipboardOwner) {
        this.captureMessageHandler = captureMessageHandler;
        this.mouseMessageHandler = mouseMessageHandler;
        this.clipboardOwner = clipboardOwner;
        fireOnReady();
    }

    @Override
    public void configure(NetworkAssistantEngineConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void reconfigure(NetworkAssistantEngineConfiguration configuration) {
        this.configuration = configuration;
    }

    public void addListener(NetworkAssistantEngineListener listener) {
        listeners.add(listener);
    }

    /**
     * Called from a GUI action => do not block the AWT thread (!)
     */
    public void start(boolean compatibilityMode) {
        if (cancelling.get() || receiver != null) {
            return;
        }
        if (UPnP.isUPnPAvailable() && !UPnP.isMappedTCP(configuration.getPort())) {
            UPnP.openPortTCP(configuration.getPort(), APP_NAME);
        }
        receiver = new Thread(new RunnableEx() {
            @Override
            protected void doRun() throws NoSuchAlgorithmException, KeyManagementException {
                NetworkAssistantEngine.this.receivingLoop(compatibilityMode);
            }
        }, "NetworkReceiver");

        receiver.start();
    }

    /**
     * Called from a GUI action => do not block the AWT thread (!)
     */
    public void cancel() {
        Log.info("Cancelling the network assistant engine...");
        cancelling.set(true);
        safeClose(server, connection, fileServer, fileConnection);
        fireOnDisconnecting();
    }

    public void manageRouterPorts(int oldPort, int newPort) {
        if (UPnP.isUPnPAvailable()) {
            UPnP.closePortTCP(oldPort);
            UPnP.openPortTCP(newPort, APP_NAME);
        }
    }

    // right, keep streams open - forever!
    @java.lang.SuppressWarnings({"squid:S2189", "squid:S2093"})
    private void receivingLoop(boolean compatibilityMode) throws NoSuchAlgorithmException, KeyManagementException {
        in = null;
        boolean introduced = false;
        boolean proceed = true;

        try {
            awaitConnections(compatibilityMode);
            startFileReceiver();
            initSender(8);
            initInputStream();

            while (proceed) {
                NetworkMessage.unmarshallMagicNumber(in); // blocking read (!)
                NetworkMessageType type = NetworkMessage.unmarshallEnum(in, NetworkMessageType.class);
                Log.debug("Received %s", type::name);

                if (introduced) {
                    proceed = processIntroduced(type, in);
                } else {
                    introduced = processUnIntroduced(type, in);
                }
            }
        } catch (IOException ex) {
            handleIOException(ex);
        } catch (CertificateEncodingException ex) {
            Log.error(ex.getMessage());
        } finally {
            closeConnections();
            UPnP.closePortTCP(configuration.getPort());
            fireOnReady();
        }

    }

    private void awaitConnections(boolean compatibilityMode) throws NoSuchAlgorithmException, IOException, KeyManagementException, CertificateEncodingException {
        fireOnStarting(configuration.getPort());
        ssf = CustomTrustManager.initSslContext(compatibilityMode).getServerSocketFactory();
        Log.info(format("Dayon! server [port:%d]", configuration.getPort()));
        if (compatibilityMode) {
            Log.warn("Compatibility mode enabled, using legacy certificate");
        }
        if (server != null && server.isBound()) {
            safeClose(server);
        }
        server = (SSLServerSocket) ssf.createServerSocket(configuration.getPort());
        server.setNeedClientAuth(true);
        Log.info("Accepting...");

        do {
            if (connection != null && connection.isBound()) {
                safeClose(connection); // we might have refused the accepted connection (!)
            }
            connection = (SSLSocket) server.accept();
            Toolkit.getDefaultToolkit().beep();
            if (!connection.getSession().isValid()) {
                fireOnFingerprinted(null);
                throw new IOException("Certificate error, try enabling compatibility mode!");
            }
            fireOnFingerprinted(CustomTrustManager.calculateFingerprints(connection.getSession(), this.getClass().getSimpleName()));
            Log.info(format("Incoming connection from %s", connection.getInetAddress().getHostAddress()));
        } while (!fireOnAccepted(connection) && !cancelling.get());

        if (server.isBound()) {
            safeClose(server);
        }
        server = null;
    }

    private void startFileReceiver() {
        fileReceiver = new Thread(new RunnableEx() {
            @Override
            protected void doRun() {
                NetworkAssistantEngine.this.fileReceivingLoop();
            }
        }, "FileReceiver");

        fileReceiver.start();
    }

    // right, keep streams open - forever!
    @java.lang.SuppressWarnings({"squid:S2189", "squid:S2093"})
    private void fileReceivingLoop() {
        fileIn = null;
        Log.info(format("Dayon! file server [port:%d]", configuration.getPort()));

        try {
            fileServer = (SSLServerSocket) ssf.createServerSocket(configuration.getPort());
            fileConnection = (SSLSocket) fileServer.accept();
            initFileSender();
            fileIn = new ObjectInputStream(new BufferedInputStream(fileConnection.getInputStream()));

            handleIncomingClipboardFiles(fileIn, clipboardOwner);
        } catch (IOException ex) {
            closeConnections();
        }
    }

    private boolean processIntroduced(NetworkMessageType type, ObjectInputStream in) throws IOException {
        switch (type) {
            case CAPTURE:
                final NetworkCaptureMessage capture = NetworkCaptureMessage.unmarshall(in);
                fireOnByteReceived(1 + capture.getWireSize()); // +1 : magic number (byte)
                captureMessageHandler.handleCapture(capture);
                return true;

            case MOUSE_LOCATION:
                final NetworkMouseLocationMessage mouse = NetworkMouseLocationMessage.unmarshall(in);
                fireOnByteReceived(1 + mouse.getWireSize()); // +1 : magic number (byte)
                mouseMessageHandler.handleLocation(mouse);
                return true;

            case CLIPBOARD_TEXT:
                final NetworkClipboardTextMessage clipboardTextMessage = NetworkClipboardTextMessage.unmarshall(in);
                fireOnByteReceived(1 + clipboardTextMessage.getWireSize()); // +1 : magic number (byte)
                setClipboardContents(clipboardTextMessage.getText(), clipboardOwner);
                fireOnClipboardReceived();
                return true;

            case PING:
                fireOnClipboardSent();
                return true;

            case RESIZE:
                final NetworkResizeScreenMessage resize = NetworkResizeScreenMessage.unmarshall(in);
                fireOnByteReceived(1 + resize.getWireSize()); // +1 : magic number (byte)
                fireOnResizeScreen(resize.getWidth(), resize.getHeight());
                return true;

            case GOODBYE:
                fireOnTerminating();
                return false;

            case HELLO:
                throw new IllegalArgumentException("Unexpected message [HELLO]!");

            default:
                throw new IllegalArgumentException(format(UNSUPPORTED_TYPE, type));
        }
    }

    private boolean processUnIntroduced(NetworkMessageType type, ObjectInputStream in) throws IOException {
        switch (type) {
            case HELLO:
                introduce(in);
                fireOnConnected(connection);
                return true;

            case PING:
                return false;

            case CAPTURE:
            case MOUSE_LOCATION:
            case CLIPBOARD_TEXT:
            case CLIPBOARD_FILES:
            case GOODBYE:
                throw new IllegalArgumentException(format("Unexpected message [%s]!", type.name()));

            default:
                throw new IllegalArgumentException(format(UNSUPPORTED_TYPE, type));
        }
    }

    private void introduce(ObjectInputStream in) throws IOException {
        final NetworkHelloMessage hello = NetworkHelloMessage.unmarshall(in);
        fireOnByteReceived(1 + hello.getWireSize()); // +1 : magic number (byte)
        if (!isCompatibleVersion(hello.getMajor(), hello.getMinor(), Version.get())) {
            Log.error(format("Incompatible assisted version: %d.%d", hello.getMajor(), hello.getMinor()));
            throw new IOException("version.wrong");
        }
    }

    /**
     * Might be blocking if the sender queue is full (!)
     */
    public void sendCaptureConfiguration(CaptureEngineConfiguration configuration) {
        if (sender != null) {
            sender.sendCaptureConfiguration(configuration);
        }
    }

    /**
     * Might be blocking if the sender queue is full (!)
     */
    public void sendCompressorConfiguration(CompressorEngineConfiguration configuration) {
        if (sender != null) {
            sender.sendCompressorConfiguration(configuration);
        }
    }

    /**
     * Might be blocking if the sender queue is full (!)
     */
    public void sendMouseControl(NetworkMouseControlMessage message) {
        if (sender != null) {
            sender.sendMouseControl(message);
        }
    }

    /**
     * Might be blocking if the sender queue is full (!)
     */
    public void sendKeyControl(NetworkKeyControlMessage message) {
        if (sender != null) {
            sender.sendKeyControl(message);
        }
    }

    /**
     * Might be blocking if the sender queue is full (!)
     */
    public void sendRemoteClipboardRequest() {
        if (sender != null) {
            sender.sendRemoteClipboardRequest();
        }
    }

    private void fireOnReady() {
        listeners.getListeners().forEach(NetworkAssistantEngineListener::onReady);
    }

    private void fireOnStarting(int port) {
        listeners.getListeners().forEach(listener -> listener.onStarting(port));
    }

    private boolean fireOnAccepted(Socket connection) {
        return listeners.getListeners().stream().allMatch(listener -> listener.onAccepted(connection));
    }

    private void fireOnConnected(Socket connection) {
        listeners.getListeners().forEach(listener -> listener.onConnected(connection));
    }

    private void fireOnByteReceived(int count) {
        listeners.getListeners().forEach(listener -> listener.onByteReceived(count));
    }

    @Override
    protected void fireOnClipboardReceived() {
        listeners.getListeners().forEach(NetworkAssistantEngineListener::onClipboardReceived);
    }

    private void fireOnClipboardSent() {
        listeners.getListeners().forEach(NetworkAssistantEngineListener::onClipboardSent);
    }

    private void fireOnResizeScreen(int width, int height) {
        listeners.getListeners().forEach(listener -> listener.onResizeScreen(width, height));
    }

    private void fireOnDisconnecting() {
        listeners.getListeners().forEach(NetworkAssistantEngineListener::onDisconnecting);
    }

    private void fireOnTerminating() {
        listeners.getListeners().forEach(NetworkAssistantEngineListener::onTerminating);
    }

    @Override
    protected void fireOnIOError(IOException error) {
        listeners.getListeners().forEach(listener -> listener.onIOError(error));
    }

    private void fireOnFingerprinted(String fingerprints) {
        listeners.getListeners().forEach(listener -> listener.onFingerprinted(fingerprints));
    }
}
