package mpo.dayon.assistant.control;

import mpo.dayon.assistant.network.NetworkAssistantEngine;
import mpo.dayon.common.network.message.NetworkKeyControlMessage;
import mpo.dayon.common.network.message.NetworkMouseControlMessage;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ControlEngineTest {
    private final NetworkAssistantEngine network = mock(NetworkAssistantEngine.class);
    private ControlEngine controlEngine;

    @BeforeEach
    void init() {
        controlEngine = new ControlEngine(network);
        controlEngine.start();
    }

    @Test
    void onMouseMove() {
        // when
        controlEngine.onMouseMove(1, 1);
        // then
        verify(network, timeout(100).atLeastOnce()).sendMouseControl(any(NetworkMouseControlMessage.class));
    }

    @Test
    void onMouseWheeled() {
        // when
        controlEngine.onMouseWheeled(1, 2, 3);
        // then
        verify(network, timeout(100).atLeastOnce()).sendMouseControl(any(NetworkMouseControlMessage.class));
    }

    @Test
    void onMousePressed() {
        // when
        controlEngine.onMousePressed(1, 1, 1);
        // then
        verify(network, timeout(100).atLeastOnce()).sendMouseControl(any(NetworkMouseControlMessage.class));
    }

    @Test
    void onMouseReleased() {
        // when
        controlEngine.onMouseReleased(1, 1, 2);
        // then
        verify(network, timeout(100).atLeastOnce()).sendMouseControl(any(NetworkMouseControlMessage.class));
    }

    @Test
    void onKeyPressedAndReleased() {
        // given
        final ArgumentCaptor<NetworkKeyControlMessage> keyMessageCaptor = ArgumentCaptor.forClass(NetworkKeyControlMessage.class);
        final int keyC = 67;
        final char charC = 'C';
        // when
        controlEngine.onKeyPressed(keyC, charC);
        // then
        verify(network, timeout(100).atLeastOnce()).sendKeyControl(keyMessageCaptor.capture());
        // when
        controlEngine.onKeyReleased(keyC, charC);
        // then
        verify(network, timeout(150).atLeastOnce()).sendKeyControl(keyMessageCaptor.capture());
        final List<NetworkKeyControlMessage> messages = keyMessageCaptor.getAllValues();
        NetworkKeyControlMessage first = messages.get(0);
        NetworkKeyControlMessage last = messages.get(messages.size()-1);
        assertTrue(first.isPressed());
        assertEquals(keyC, first.getKeyCode());
        assertEquals(charC, first.getKeyChar());
        assertTrue(last.isReleased());
        assertEquals(keyC, last.getKeyCode());
        assertEquals(charC, last.getKeyChar());
    }

    @Test
    void onKeyReleasedOfPreviouslyUnpressedKey() {
        // given
        final int keyD = 68;
        final char charD = 'D';
        final ArgumentCaptor<NetworkKeyControlMessage> keyMessageCaptor = ArgumentCaptor.forClass(NetworkKeyControlMessage.class);
        // when
        controlEngine.onKeyReleased(keyD, charD);
        // then
        verify(network, never()).sendKeyControl(keyMessageCaptor.capture());
    }
}