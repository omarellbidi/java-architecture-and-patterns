package observer;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MainSpreader is the central broadcaster for spreading news.
 * Implements the Subject and NewsSpreader interfaces.
 * Thread-safe: uses ConcurrentHashMap and CopyOnWriteArrayList for concurrent access.
 */
public class MainSpreader implements Subject, NewsSpreader {

    private final TrustedSourceManager sourceManager;
    private final ContentFilter contentFilter;
    /** Topic -> list of observers. "all" is the catch-all topic. */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<NewsObserver>> topicObservers;

    public MainSpreader() {
        this.sourceManager  = new TrustedSourceManager();
        this.contentFilter  = new ContentFilter();
        this.topicObservers = new ConcurrentHashMap<>();
    }

    // Registers a trusted news source with a hashed password
    @Override
    public boolean registerTrustedSource(String source, String pwd) {
        if (source == null || pwd == null || source.isEmpty() || pwd.isEmpty()) {
            return false;
        }
        return sourceManager.addSource(source, hashPassword(pwd));
    }

    // Adds a word to the block list with redaction options
    @Override
    public boolean blockWord(String word, boolean redact) {
        return contentFilter.blockWord(word, redact);
    }

    // Removes a word from the block list
    @Override
    public boolean unblockWord(String word) {
        return contentFilter.unblockWord(word);
    }

    /**
     * Validates the source credentials, filters the news content, then
     * notifies all observers subscribed to the news's topic (or "all").
     */
    @Override
    public String spreadNews(String news, String source, String pwd) throws NewsSpreaderException {
        if (news == null || source == null || pwd == null) {
            throw new IllegalArgumentException("News, source, or password cannot be null.");
        }
        if (!sourceManager.isSourceRegistered(source)) {
            throw new UntrustedSourceException("Source not registered: " + source);
        }
        if (!sourceManager.authenticateSource(source, hashPassword(pwd))) {
            throw new AuthenticationException("Failed authentication for source: " + source);
        }

        String processedNews = contentFilter.processNews(news);
        LocalDateTime timestamp = LocalDateTime.now();
        notifyObservers(processedNews, source, timestamp, extractTopic(news));
        return processedNews;
    }

    /** Registers an observer for all topics (catch-all). */
    @Override
    public void registerObserver(NewsObserver observer) {
        registerObserver(observer, "all");
    }

    /** Registers an observer for a specific topic. */
    @Override
    public void registerObserver(NewsObserver observer, String topic) {
        if (observer == null || topic == null || topic.isEmpty()) return;
        topicObservers
            .computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
            .addIfAbsent(observer);
    }

    /** Removes an observer from every topic it is registered under. */
    @Override
    public void unregisterObserver(NewsObserver observer) {
        topicObservers.values().forEach(list -> list.remove(observer));
    }

    /** Notifies observers on the catch-all ("all") topic. */
    @Override
    public void notifyObservers(String news, String source, LocalDateTime timestamp) {
        notifyObservers(news, source, timestamp, "all");
    }

    /** Notifies observers subscribed to the specified topic. */
    public void notifyObservers(String news, String source, LocalDateTime timestamp, String topic) {
        CopyOnWriteArrayList<NewsObserver> topicList =
                topicObservers.getOrDefault(topic, new CopyOnWriteArrayList<>());
        CopyOnWriteArrayList<NewsObserver> allList =
                topicObservers.getOrDefault("all", new CopyOnWriteArrayList<>());

        topicList.forEach(o -> o.update(news, source, timestamp));
        // also notify catch-all observers if topic is not "all"
        if (!"all".equals(topic)) {
            allList.forEach(o -> o.update(news, source, timestamp));
        }
    }

    /** Extracts the first hashtag from the news as a topic, or returns "all". */
    private String extractTopic(String news) {
        if (news.contains("#")) {
            return Arrays.stream(news.split("\\s+"))
                    .filter(word -> word.startsWith("#"))
                    .findFirst()
                    .orElse("all");
        }
        return "all";
    }

    /** Produces a hex-encoded SHA-256 hash of the given password. */
    static String hashPassword(String password) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] encodedHash =
                    digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(encodedHash.length * 2);
            for (byte b : encodedHash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}


