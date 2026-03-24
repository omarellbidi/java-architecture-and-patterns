package bank.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Manages database connections using HikariCP — a high-performance, production-ready
 * JDBC connection pool. Implemented as a thread-safe Singleton.
 *
 * <p>Configuration is loaded from {@code config/database.properties}. The application
 * fails fast with a clear message if the configuration file is missing, rather than
 * silently using insecure hardcoded credentials.
 */
public class DatabaseConnectionManager {

    private static volatile DatabaseConnectionManager instance;
    private HikariDataSource dataSource; // non-final to allow test subclassing


    protected DatabaseConnectionManager() {
        this.dataSource = buildDataSource();
    }

    /**
     * Protected no-arg constructor for test subclasses that override
     * {@link #getConnection()} and do not need a real datasource.
     * Pass {@code false} to skip datasource initialization.
     */
    protected DatabaseConnectionManager(boolean initDataSource) {
        this.dataSource = initDataSource ? buildDataSource() : null;
    }



    /**
     * Returns the singleton instance (double-checked locking, thread-safe).
     */
    public static DatabaseConnectionManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseConnectionManager.class) {
                if (instance == null) {
                    instance = new DatabaseConnectionManager();
                }
            }
        }
        return instance;
    }

    // ── Internal setup ────────────────────────────────────────────────────────

    private HikariDataSource buildDataSource() {
        Properties props = loadDatabaseProperties();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.getProperty("db.url"));
        config.setUsername(props.getProperty("db.username"));
        config.setPassword(props.getProperty("db.password"));

        // Pool tuning — all values can be overridden in database.properties
        config.setMaximumPoolSize(
                Integer.parseInt(props.getProperty("db.maxPoolSize", "10")));
        config.setMinimumIdle(
                Integer.parseInt(props.getProperty("db.minIdle", "2")));
        config.setConnectionTimeout(30_000);   // 30 s — max wait for a connection
        config.setIdleTimeout(600_000);         // 10 min — idle before eviction
        config.setMaxLifetime(1_800_000);       // 30 min — absolute max connection age
        config.setPoolName("BankingPool");

        // Validate connections before handing them out
        config.setConnectionTestQuery("SELECT 1");

        return new HikariDataSource(config);
    }

    private Properties loadDatabaseProperties() {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream("config/database.properties")) {
            props.load(fis);
        } catch (IOException e) {
            throw new DatabaseException(
                    "Cannot load config/database.properties. " +
                    "Copy config/database.properties.example and fill in your credentials.", e);
        }
        validateRequired(props, "db.url");
        validateRequired(props, "db.username");
        validateRequired(props, "db.password");
        return props;
    }

    private void validateRequired(Properties props, String key) {
        if (props.getProperty(key) == null || props.getProperty(key).isBlank()) {
            throw new DatabaseException("Required property '" + key +
                    "' is missing in config/database.properties");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Borrows a connection from the HikariCP pool.
     * The caller is responsible for returning it via {@link #releaseConnection(Connection)}.
     */
    public Connection getConnection() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to acquire database connection from pool", e);
        }
    }

    /**
     * Returns a connection to the pool.
     * Closing a HikariCP connection returns it to the pool — it is not physically closed.
     */
    public void releaseConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close(); // logical close — returns to pool
            } catch (SQLException e) {
                throw new DatabaseException("Failed to release connection back to pool", e);
            }
        }
    }

    /**
     * Shuts down the connection pool. Call this once on application exit.
     */
    public void closeAllConnections() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}