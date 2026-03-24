package observer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TrustedSourceManager}.
 */
@DisplayName("TrustedSourceManager")
class TrustedSourceManagerTest {

    private TrustedSourceManager manager;

    @BeforeEach
    void setUp() {
        manager = new TrustedSourceManager();
    }

    // ── addSource ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addSource returns true for a new source")
    void addSource_newSource_returnsTrue() {
        assertTrue(manager.addSource("cnn", "hash123"));
    }

    @Test
    @DisplayName("addSource returns false when source already exists")
    void addSource_duplicate_returnsFalse() {
        manager.addSource("bbc", "hashA");
        assertFalse(manager.addSource("bbc", "hashB"),
                "Duplicate source registration should be rejected");
    }

    @Test
    @DisplayName("addSource does not overwrite the password on duplicate attempt")
    void addSource_duplicate_passwordUnchanged() {
        manager.addSource("rt", "originalHash");
        manager.addSource("rt", "newHash");   // should be ignored
        assertTrue(manager.authenticateSource("rt", "originalHash"),
                "Original password should remain after duplicate addSource");
    }

    // ── isSourceRegistered ────────────────────────────────────────────────────

    @Test
    @DisplayName("isSourceRegistered returns true after source is added")
    void isSourceRegistered_afterAdd_returnsTrue() {
        manager.addSource("reuters", "hash");
        assertTrue(manager.isSourceRegistered("reuters"));
    }

    @Test
    @DisplayName("isSourceRegistered returns false for unknown source")
    void isSourceRegistered_unknownSource_returnsFalse() {
        assertFalse(manager.isSourceRegistered("unknown"));
    }

    // ── authenticateSource ────────────────────────────────────────────────────

    @Test
    @DisplayName("authenticateSource returns true for correct hash")
    void authenticateSource_correctHash_returnsTrue() {
        manager.addSource("ap", "correctHash");
        assertTrue(manager.authenticateSource("ap", "correctHash"));
    }

    @Test
    @DisplayName("authenticateSource returns false for wrong hash")
    void authenticateSource_wrongHash_returnsFalse() {
        manager.addSource("ap", "correctHash");
        assertFalse(manager.authenticateSource("ap", "wrongHash"));
    }

    @Test
    @DisplayName("authenticateSource returns false for unregistered source")
    void authenticateSource_unregisteredSource_returnsFalse() {
        assertFalse(manager.authenticateSource("nobody", "anyHash"));
    }
}
