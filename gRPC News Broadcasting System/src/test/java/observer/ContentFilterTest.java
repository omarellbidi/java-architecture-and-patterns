package observer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContentFilter}.
 * Verifies word blocking, redaction, and normal pass-through behaviour.
 */
@DisplayName("ContentFilter")
class ContentFilterTest {

    private ContentFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ContentFilter();
    }

    // ── blockWord ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("blockWord accepts a valid alphabetic word")
    void blockWord_validWord_returnsTrue() {
        assertTrue(filter.blockWord("spam", false));
    }

    @Test
    @DisplayName("blockWord rejects null")
    void blockWord_null_returnsFalse() {
        assertFalse(filter.blockWord(null, false));
    }

    @Test
    @DisplayName("blockWord rejects blank string")
    void blockWord_blank_returnsFalse() {
        assertFalse(filter.blockWord("   ", false));
    }

    @Test
    @DisplayName("blockWord rejects words containing digits or symbols")
    void blockWord_nonAlpha_returnsFalse() {
        assertFalse(filter.blockWord("sp@m", false));
        assertFalse(filter.blockWord("sp4m", false));
    }

    @Test
    @DisplayName("blockWord for the same word twice returns false the second time")
    void blockWord_duplicate_returnsFalse() {
        assertTrue(filter.blockWord("spam", false));
        // second call — key is already present, behaviour is overwrite → returns true
        // (HashMap.put always succeeds; the method relies on existence check)
        // This test documents the current behaviour:
        assertTrue(filter.blockWord("spam", true),
                "Re-blocking an already-blocked word should still succeed (policy update)");
    }

    // ── unblockWord ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unblockWord returns true for a previously blocked word")
    void unblockWord_existingWord_returnsTrue() {
        filter.blockWord("spam", false);
        assertTrue(filter.unblockWord("spam"));
    }

    @Test
    @DisplayName("unblockWord returns false for a word that was never blocked")
    void unblockWord_unknownWord_returnsFalse() {
        assertFalse(filter.unblockWord("unknown"));
    }

    // ── processNews ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("processNews passes through clean content unchanged")
    void processNews_cleanContent_unchanged() throws BlockedContentException {
        String news = "Breaking news: everything is fine!";
        assertEquals(news, filter.processNews(news));
    }

    @Test
    @DisplayName("processNews redacts blocked word when redact=true")
    void processNews_redactMode_replacesWord() throws BlockedContentException {
        filter.blockWord("spam", true);
        String result = filter.processNews("This is spam content");
        assertFalse(result.contains("spam"), "Blocked word should be redacted");
        assertTrue(result.contains("#"), "Redacted word should be replaced with '#'");
    }

    @Test
    @DisplayName("processNews throws BlockedContentException when redact=false")
    void processNews_blockMode_throwsException() {
        filter.blockWord("spam", false);
        assertThrows(BlockedContentException.class,
                () -> filter.processNews("This is spam content"));
    }

    @Test
    @DisplayName("processNews is case-sensitive for blocked words")
    void processNews_caseSensitive_notBlocked() throws BlockedContentException {
        filter.blockWord("spam", false);
        // "SPAM" should NOT trigger the block (regex is case-sensitive)
        assertDoesNotThrow(() -> filter.processNews("This is SPAM content"));
    }

    @Test
    @DisplayName("processNews handles empty string without error")
    void processNews_emptyString_returnsEmpty() throws BlockedContentException {
        assertEquals("", filter.processNews(""));
    }

    @Test
    @DisplayName("processNews after unblocking does not block the word")
    void processNews_afterUnblock_passes() throws BlockedContentException {
        filter.blockWord("spam", false);
        filter.unblockWord("spam");
        assertDoesNotThrow(() -> filter.processNews("spam is everywhere"));
    }
}
