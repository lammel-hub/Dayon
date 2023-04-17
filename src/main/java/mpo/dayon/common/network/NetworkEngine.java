package mpo.dayon.common.network;

import mpo.dayon.common.log.Log;
import mpo.dayon.common.network.message.NetworkClipboardFilesHelper;
import mpo.dayon.common.network.message.NetworkClipboardFilesMessage;
import mpo.dayon.common.network.message.NetworkMessage;
import mpo.dayon.common.network.message.NetworkMessageType;
import mpo.dayon.common.security.CustomTrustManager;

import javax.net.ssl.*;
import java.awt.*;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.String.format;
import static mpo.dayon.common.network.message.NetworkMessageType.CLIPBOARD_FILES;
import static mpo.dayon.common.network.message.NetworkMessageType.PING;
import static mpo.dayon.common.security.CustomTrustManager.KEY_STORE_PASS;
import static mpo.dayon.common.security.CustomTrustManager.KEY_STORE_PATH;
import static mpo.dayon.common.utils.SystemUtilities.*;

/**
 * Both the assistant and the assisted are talking to each other using a very
 * simple asynchronous network message layer. The network engine is handling
 * both the sending and the receiving sides.
 */
public abstract class NetworkEngine implements HandshakeCompletedListener {

    protected static final String UNSUPPORTED_TYPE = "Unsupported message type [%s]!";

    protected NetworkSender sender; // out

    protected NetworkSender fileSender; // file out

    protected Thread receiver; // in

    protected ObjectInputStream in;

    protected Thread fileReceiver; // file in

    protected ObjectInputStream fileIn;

    protected SSLServerSocket server;

    protected SSLSocket connection;

    protected SSLServerSocket fileServer;

    protected SSLSocket fileConnection;

    protected final AtomicBoolean cancelling = new AtomicBoolean(false);

    /**
     * Might be blocking if the sender queue is full (!)
     */
    public void sendClipboardText(String text) {
        if (sender != null) {
            sender.sendClipboardContentText(text, text.getBytes().length);
        }
    }

    /**
     * Might be blocking if the sender queue is full (!)
     */
    public void sendClipboardFiles(List<File> files, long size, String basePath) {
        if (fileSender != null) {
            fileSender.sendClipboardContentFiles(files, size, basePath);
        }
    }

    protected void setClipboardContents(String string, ClipboardOwner clipboardOwner) {
        Log.debug("setClipboardContents %s", () -> string);
        StringSelection stringSelection = new StringSelection(string);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, clipboardOwner);
    }

    private void setClipboardContents(List<File> files, ClipboardOwner clipboardOwner) {
        Log.debug("setClipboardContents %s", () -> String.valueOf(files));
        TransferableFiles transferableFiles = new TransferableFiles(files);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferableFiles, clipboardOwner);
    }

    private NetworkClipboardFilesHelper handleNetworkClipboardFilesHelper(NetworkClipboardFilesHelper filesHelper, ClipboardOwner clipboardOwner) {
        if (filesHelper.isDone()) {
            setClipboardContents(filesHelper.getFiles(), clipboardOwner);
            return new NetworkClipboardFilesHelper();
        }
        return filesHelper;
    }

    @java.lang.SuppressWarnings("squid:S6437") // pro forma password, without security relevance
    protected SSLContext initSSLContext() throws NoSuchAlgorithmException, IOException, KeyManagementException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(NetworkEngine.class.getResourceAsStream(KEY_STORE_PATH), KEY_STORE_PASS.toCharArray());
            kmf.init(keyStore, KEY_STORE_PASS.toCharArray());
        } catch (KeyStoreException | CertificateException | UnrecoverableKeyException e) {
            Log.error("Fatal, can not init encryption", e);
            throw new UnsupportedOperationException(e);
        }
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), new TrustManager[]{new CustomTrustManager()}, new SecureRandom());
        return sslContext;
    }

    protected void initSender(int queueSize) throws IOException {
        sender = new NetworkSender(new ObjectOutputStream(new BufferedOutputStream(connection.getOutputStream())));
        sender.start(queueSize);
        sender.ping();
    }

    protected void initFileSender() throws IOException {
        fileSender = new NetworkSender(new ObjectOutputStream(new BufferedOutputStream(fileConnection.getOutputStream())));
        fileSender.start(1);
        fileSender.ping();
    }

    protected void handleIncomingClipboardFiles(ObjectInputStream fileIn, ClipboardOwner clipboardOwner) throws IOException {
        String tmpDir = getTempDir();
        NetworkClipboardFilesHelper filesHelper = new NetworkClipboardFilesHelper();

        //noinspection InfiniteLoopStatement
        while (true) {
            NetworkMessageType type;
            if (filesHelper.isDone()) {
                NetworkMessage.unmarshallMagicNumber(fileIn); // blocking read (!)
                type = NetworkMessage.unmarshallEnum(fileIn, NetworkMessageType.class);
                Log.debug("Received " + type.name());
            } else {
                type = CLIPBOARD_FILES;
            }

            if (type.equals(CLIPBOARD_FILES)) {
                filesHelper = handleNetworkClipboardFilesHelper(NetworkClipboardFilesMessage.unmarshall(fileIn,
                        filesHelper, tmpDir), clipboardOwner);
                if (filesHelper.isDone()) {
                    fireOnClipboardReceived();
                }
            } else if (!type.equals(PING)) {
                throw new IllegalArgumentException(format(UNSUPPORTED_TYPE, type));
            }
        }
    }

    protected void fireOnClipboardReceived() {
    }

    protected void closeConnections() {
        if (sender != null) {
            sender.cancel();
        }
        receiver = safeInterrupt(receiver);
        safeClose(in, connection, server);

        if (fileSender != null) {
            fileSender.cancel();
        }
        fileReceiver = safeInterrupt(fileReceiver);
        safeClose(fileIn, fileConnection, fileServer);
        cancelling.set(false);
    }

    protected void initInputStream() throws IOException {
        try {
            in = new ObjectInputStream(new BufferedInputStream(connection.getInputStream()));
        } catch (StreamCorruptedException ex) {
            throw new IOException("version.wrong");
        }
    }

    protected void handleIOException(IOException ex) {
        if (!cancelling.get()) {
            Log.error("IO error (not cancelled)", ex);
            fireOnIOError(ex);
        } else {
            Log.info("Stopped network receiver (cancelled)");
        }
    }

    protected void fireOnIOError(IOException error) {
    }

    protected void fireOnCertError(String fingerprint) {
    }

    @Override
    public void handshakeCompleted(HandshakeCompletedEvent event) {
        try {
            String fingerprint = CustomTrustManager.calculateFingerprint(event.getPeerCertificates()[0]);
            if (!CustomTrustManager.isValidFingerprint(fingerprint)) {
                Log.warn(format("Peer certificate does not match fingerprint '%s' !", fingerprint));
                fireOnCertError(fingerprint);
            }
        } catch (SSLPeerUnverifiedException | NoSuchAlgorithmException | CertificateEncodingException e) {
            Log.error(e.getMessage());
            throw new IllegalArgumentException(e);
        }
    }

}
