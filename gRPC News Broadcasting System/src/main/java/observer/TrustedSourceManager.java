package observer;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages trusted news sources.
 * Thread-safe: uses ConcurrentHashMap for concurrent registration and lookup.
 */
public class TrustedSourceManager {

    private final ConcurrentHashMap<String, String> trustedSources = new ConcurrentHashMap<>();

    /**
     * Registers a new trusted source with its hashed password.
     * @return true if added; false if the source already exists.
     */
    public boolean addSource(String source, String hashedPassword) {
        // putIfAbsent is atomic — returns null when the key was new
        return trustedSources.putIfAbsent(source, hashedPassword) == null;
    }

    /**
     * @return true if the source name is already registered.
     */
    public boolean isSourceRegistered(String source) {
        return trustedSources.containsKey(source);
    }

    /**
     * Verifies the hashed password for the given source.
     * @return true if the password matches the stored hash.
     */
    public boolean authenticateSource(String source, String hashedPassword) {
        return hashedPassword.equals(trustedSources.get(source));
    }
}

