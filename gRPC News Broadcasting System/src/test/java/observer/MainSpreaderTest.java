package observer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style unit tests for {@link MainSpreader}.
 * Verifies the full publish-subscribe flow, authentication, content filtering,
 * topic routing, and observer lifecycle without any gRPC infrastructure.
 */
@DisplayName("MainSpreader")
class MainSpreaderTest {

    private MainSpreader spreader;
    private static final String SOURCE    = "testSource";
    private static final String PASSWORD  = "secret";

    /** A simple test observer that records received messages. */
    static class RecordingObserver implements NewsObserver {
        final List<String> received = new ArrayList<>();
        @Override
        public void update(String news, String source, LocalDateTime timestamp) {
            received.add(news);
        }
    }

    @BeforeEach
    void setUp() {
        spreader = new MainSpreader();
        spreader.registerTrustedSource(SOURCE, PASSWORD);
    }

    // ── registerTrustedSource ─────────────────────────────────────────────────

    @Test
    @DisplayName("registerTrustedSource returns true for a new source")
    void registerTrustedSource_newSource_returnsTrue() {
        assertTrue(spreader.registerTrustedSource("newSource", "pwd"));
    }

    @Test
    @DisplayName("registerTrustedSource rejects null arguments")
    void registerTrustedSource_nullArgs_returnsFalse() {
        assertFalse(spreader.registerTrustedSource(null, "pwd"));
        assertFalse(spreader.registerTrustedSource("src", null));
    }

    @Test
    @DisplayName("registerTrustedSource rejects empty strings")
    void registerTrustedSource_emptyArgs_returnsFalse() {
        assertFalse(spreader.registerTrustedSource("", "pwd"));
        assertFalse(spreader.registerTrustedSource("src", ""));
    }

    // ── spreadNews ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("spreadNews delivers news to registered observers")
    void spreadNews_validCredentials_observerReceivesNews() throws NewsSpreaderException {
        RecordingObserver observer = new RecordingObserver();
        spreader.registerObserver(observer);

        spreader.spreadNews("Hello World", SOURCE, PASSWORD);

        assertEquals(1, observer.received.size());
        assertEquals("Hello World", observer.received.get(0));
    }

    @Test
    @DisplayName("spreadNews throws UntrustedSourceException for unknown source")
    void spreadNews_unknownSource_throwsUntrustedSourceException() {
        assertThrows(UntrustedSourceException.class,
                () -> spreader.spreadNews("news", "ghost", "pwd"));
    }

    @Test
    @DisplayName("spreadNews throws AuthenticationException for wrong password")
    void spreadNews_wrongPassword_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class,
                () -> spreader.spreadNews("news", SOURCE, "wrongPwd"));
    }

    @Test
    @DisplayName("spreadNews throws IllegalArgumentException for null arguments")
    void spreadNews_nullArgs_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> spreader.spreadNews(null, SOURCE, PASSWORD));
    }

    @Test
    @DisplayName("spreadNews throws BlockedContentException for blocked non-redacted word")
    void spreadNews_blockedWord_throwsBlockedContentException() {
        spreader.blockWord("bomb", false);
        assertThrows(BlockedContentException.class,
                () -> spreader.spreadNews("There is a bomb here", SOURCE, PASSWORD));
    }

    @Test
    @DisplayName("spreadNews redacts blocked word and still delivers news")
    void spreadNews_redactedWord_deliveredWithRedaction() throws NewsSpreaderException {
        spreader.blockWord("spam", true);
        RecordingObserver observer = new RecordingObserver();
        spreader.registerObserver(observer);

        spreader.spreadNews("This is spam content", SOURCE, PASSWORD);

        assertEquals(1, observer.received.size());
        assertFalse(observer.received.get(0).contains("spam"));
    }

    // ── topic routing ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Topic observer receives news with matching hashtag")
    void topicRouting_matchingHashtag_receivesNews() throws NewsSpreaderException {
        RecordingObserver topicObserver = new RecordingObserver();
        spreader.registerObserver(topicObserver, "#sports");

        spreader.spreadNews("Great match! #sports", SOURCE, PASSWORD);

        assertEquals(1, topicObserver.received.size());
    }

    @Test
    @DisplayName("Catch-all observer receives every news item regardless of topic")
    void topicRouting_catchAllObserver_receivesAllNews() throws NewsSpreaderException {
        RecordingObserver allObserver = new RecordingObserver(); // registered under "all"
        spreader.registerObserver(allObserver);

        spreader.spreadNews("Breaking #politics story", SOURCE, PASSWORD);
        spreader.spreadNews("Plain news without topic",   SOURCE, PASSWORD);

        assertEquals(2, allObserver.received.size());
    }

    @Test
    @DisplayName("Topic observer does NOT receive news for a different topic")
    void topicRouting_differentTopic_doesNotReceive() throws NewsSpreaderException {
        RecordingObserver sportsObserver = new RecordingObserver();
        spreader.registerObserver(sportsObserver, "#sports");

        spreader.spreadNews("Political update #politics", SOURCE, PASSWORD);

        assertTrue(sportsObserver.received.isEmpty(),
                "#sports observer should not receive #politics news");
    }

    // ── observer lifecycle ────────────────────────────────────────────────────

    @Test
    @DisplayName("unregisterObserver stops delivery to removed observer")
    void unregisterObserver_removedObserver_stopsReceiving() throws NewsSpreaderException {
        RecordingObserver observer = new RecordingObserver();
        spreader.registerObserver(observer);
        spreader.spreadNews("First news", SOURCE, PASSWORD);

        spreader.unregisterObserver(observer);
        spreader.spreadNews("Second news", SOURCE, PASSWORD);

        assertEquals(1, observer.received.size(), "Observer should have received only the first news");
    }

    @Test
    @DisplayName("Same observer is not registered twice (addIfAbsent semantics)")
    void registerObserver_twice_receivesOnce() throws NewsSpreaderException {
        RecordingObserver observer = new RecordingObserver();
        spreader.registerObserver(observer);
        spreader.registerObserver(observer); // second call should be ignored

        spreader.spreadNews("Hello", SOURCE, PASSWORD);

        assertEquals(1, observer.received.size(), "Observer should receive the news exactly once");
    }

    // ── blockWord / unblockWord ───────────────────────────────────────────────

    @Test
    @DisplayName("blockWord returns false for invalid input")
    void blockWord_invalid_returnsFalse() {
        assertFalse(spreader.blockWord(null, false));
        assertFalse(spreader.blockWord("", true));
        assertFalse(spreader.blockWord("bad-word!", false));
    }

    @Test
    @DisplayName("unblockWord re-enables previously blocked word")
    void unblockWord_thenSpread_passes() throws NewsSpreaderException {
        spreader.blockWord("spam", false);
        spreader.unblockWord("spam");

        RecordingObserver observer = new RecordingObserver();
        spreader.registerObserver(observer);

        spreader.spreadNews("spam is everywhere now", SOURCE, PASSWORD);
        assertEquals(1, observer.received.size());
    }
}
