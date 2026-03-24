package bank.database;

import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link TransactionService} using an H2 in-memory database.
 * No MySQL installation is required — H2 is fully self-contained.
 *
 * <p>A complete schema is created before each test and dropped after,
 * giving every test a clean slate.
 */
@DisplayName("TransactionService — H2 Integration Tests")
class TransactionServiceTest {

    // H2 in-memory URL; MODE=MySQL makes H2 accept standard MySQL SQL syntax
    private static final String JDBC_URL =
            "jdbc:h2:mem:banktest;DB_CLOSE_DELAY=-1;MODE=MySQL";

    private Connection conn;
    private TransactionService service;

    // ── Schema DDL ────────────────────────────────────────────────────────────

    private void createSchema() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                "CREATE TABLE accounts (" +
                "  id            VARCHAR(100) PRIMARY KEY," +
                "  balance       DECIMAL(15,2) NOT NULL DEFAULT 0.00," +
                "  customer_id   VARCHAR(100)  NOT NULL," +
                "  account_type  VARCHAR(20)   NOT NULL)"
            );
            stmt.execute(
                "CREATE TABLE transactions (" +
                "  id                 BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  account_id         VARCHAR(100) NOT NULL," +
                "  transaction_type   VARCHAR(30)  NOT NULL," +
                "  amount             DECIMAL(15,2) NOT NULL," +
                "  related_account_id VARCHAR(100)," +
                "  transaction_date   TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
            );
            stmt.execute(
                "CREATE TABLE audit_log (" +
                "  id           BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  action_type  VARCHAR(30)  NOT NULL," +
                "  entity_type  VARCHAR(30)  NOT NULL," +
                "  entity_id    VARCHAR(200) NOT NULL," +
                "  description  VARCHAR(500)," +
                "  created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
            );
            stmt.execute(
                "CREATE TABLE account_owners (" +
                "  account_id   VARCHAR(100) NOT NULL," +
                "  customer_id  VARCHAR(100) NOT NULL," +
                "  PRIMARY KEY (account_id, customer_id))"
            );
        }
    }

    // ── JUnit lifecycle ───────────────────────────────────────────────────────

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection(JDBC_URL, "sa", "");
        createSchema();

        // Build TransactionService with a connection manager that delegates to our H2 connection
        service = new TransactionService(new FixedConnectionManager(conn));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
        }
        conn.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void insertAccount(String id, BigDecimal balance) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO accounts (id, balance, customer_id, account_type) VALUES (?,?,?,?)")) {
            ps.setString(1, id);
            ps.setBigDecimal(2, balance);
            ps.setString(3, "cust-test");
            ps.setString(4, "PERSONAL");
            ps.executeUpdate();
        }
    }

    private BigDecimal queryBalance(String accountId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance FROM accounts WHERE id = ?")) {
            ps.setString(1, accountId);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "Account must exist: " + accountId);
            return rs.getBigDecimal("balance");
        }
    }

    private long countRows(String table, String whereCol, String whereVal) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE " + whereCol + " = ?")) {
            ps.setString(1, whereVal);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countAudit(String actionType) throws Exception {
        return countRows("audit_log", "action_type", actionType);
    }

    // ── recordDeposit ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("deposit increases balance and logs a transaction + audit entry")
    void recordDeposit_success() throws Exception {
        insertAccount("acc-1", new BigDecimal("100.00"));

        boolean ok = service.recordDeposit("acc-1", new BigDecimal("50.00"));

        assertTrue(ok);
        assertEquals(new BigDecimal("150.00"), queryBalance("acc-1"));
        assertEquals(1, countRows("transactions", "account_id", "acc-1"));
        assertEquals(1, countAudit("DEPOSIT"));
    }

    @Test
    @DisplayName("deposit returns false when account does not exist (rolls back)")
    void recordDeposit_unknownAccount_returnsFalse() throws Exception {
        boolean ok = service.recordDeposit("ghost", new BigDecimal("50.00"));

        assertFalse(ok);
        assertEquals(0, countRows("transactions", "account_id", "ghost"));
    }

    // ── recordWithdrawal ──────────────────────────────────────────────────────

    @Test
    @DisplayName("withdraw decreases balance when sufficient funds")
    void recordWithdrawal_sufficientFunds() throws Exception {
        insertAccount("acc-2", new BigDecimal("200.00"));

        boolean ok = service.recordWithdrawal("acc-2", new BigDecimal("80.00"), false);

        assertTrue(ok);
        assertEquals(new BigDecimal("120.00"), queryBalance("acc-2"));
        assertEquals(1, countAudit("WITHDRAWAL"));
    }

    @Test
    @DisplayName("withdraw returns false on insufficient funds and does not alter balance")
    void recordWithdrawal_insufficientFunds() throws Exception {
        insertAccount("acc-3", new BigDecimal("10.00"));

        boolean ok = service.recordWithdrawal("acc-3", new BigDecimal("100.00"), false);

        assertFalse(ok);
        assertEquals(new BigDecimal("10.00"), queryBalance("acc-3"));
        assertEquals(0, countRows("transactions", "account_id", "acc-3"));
    }

    @Test
    @DisplayName("withdraw allows overdraft for corporate accounts (allowNegative=true)")
    void recordWithdrawal_corporateOverdraft() throws Exception {
        insertAccount("corp", new BigDecimal("0.00"));

        boolean ok = service.recordWithdrawal("corp", new BigDecimal("500.00"), true);

        assertTrue(ok);
        assertEquals(new BigDecimal("-500.00"), queryBalance("corp"));
    }

    // ── recordTransfer ────────────────────────────────────────────────────────

    @Test
    @DisplayName("transfer moves funds atomically between two accounts")
    void recordTransfer_success() throws Exception {
        insertAccount("from-acc", new BigDecimal("300.00"));
        insertAccount("to-acc",   new BigDecimal("50.00"));

        boolean ok = service.recordTransfer("from-acc", "to-acc",
                new BigDecimal("100.00"), false);

        assertTrue(ok);
        assertEquals(new BigDecimal("200.00"), queryBalance("from-acc"));
        assertEquals(new BigDecimal("150.00"), queryBalance("to-acc"));
        assertEquals(1, countRows("transactions", "account_id", "from-acc"));
        assertEquals(1, countRows("transactions", "account_id", "to-acc"));
        assertEquals(1, countAudit("TRANSFER"));
    }

    @Test
    @DisplayName("transfer rolls back both sides on insufficient funds")
    void recordTransfer_insufficientFunds_rollsBack() throws Exception {
        insertAccount("broke", new BigDecimal("10.00"));
        insertAccount("dest",  new BigDecimal("0.00"));

        boolean ok = service.recordTransfer("broke", "dest",
                new BigDecimal("9999.00"), false);

        assertFalse(ok);
        assertEquals(new BigDecimal("10.00"), queryBalance("broke"));
        assertEquals(new BigDecimal("0.00"),  queryBalance("dest"));
        assertEquals(0, countRows("transactions", "account_id", "broke"));
    }

    // ── recordAccountOwnership ────────────────────────────────────────────────

    @Test
    @DisplayName("recordAccountOwnership creates one row per customer and logs an audit entry")
    void recordAccountOwnership_success() throws Exception {
        boolean ok = service.recordAccountOwnership("corp-acc",
                new String[]{"cust-A", "cust-B", "cust-C"});

        assertTrue(ok);
        assertEquals(3, countRows("account_owners", "account_id", "corp-acc"));
        assertEquals(1, countAudit("UPDATE_OWNERSHIP"));
    }

    // ── H2 connection manager stub ────────────────────────────────────────────

    /**
     * Returns the single H2 test connection without going through the HikariCP singleton.
     * Auto-commit is re-enabled after each logical operation so the test
     * can inspect results immediately.
     */
    private static class FixedConnectionManager extends DatabaseConnectionManager {

        private final Connection h2Connection;

        FixedConnectionManager(Connection conn) {
            super(false); // skip HikariCP — no config/database.properties needed
            this.h2Connection = conn;
        }

        @Override
        public Connection getConnection()              { return h2Connection; }

        @Override
        public void releaseConnection(Connection c)   { /* managed by the test */ }

        @Override
        public void closeAllConnections()              { /* managed by the test lifecycle */ }
    }
}

