package devmesh.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TuiLayoutTest {
    @Test
    void clipsLongLabelsToTerminalWidth() {
        String clipped = TuiLayout.clip("abcdefghijklmnopqrstuvwxyz", 8);
        assertEquals(8, clipped.codePointCount(0, clipped.length()));
        assertTrue(clipped.endsWith("…"));
    }

    @Test
    void hidesRightStatusWhenItCannotFit() {
        String status = TuiLayout.statusLine("NORMAL [INSERT]", "model · Thinking ON", 20);
        assertFalse(status.contains("Thinking ON"));
        assertTrue(status.startsWith("NORMAL"));
    }

    @Test
    void preservesBothStatusSidesWhenThereIsRoom() {
        String status = TuiLayout.statusLine("NORMAL", "model", 30);
        assertTrue(status.contains("NORMAL"));
        assertTrue(status.contains("model"));
        assertEquals(30, status.length());
    }
}
