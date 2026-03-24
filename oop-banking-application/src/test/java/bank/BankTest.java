package bank;

import bank.database.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Bank} using hand-written stubs — no Mockito required.
 * All external dependencies are replaced with minimal in-process fakes so
 * these tests need no database, no config files, and no JVM agent.
 */
@DisplayName("Bank — Unit Tests")
class BankTest {

    // ── Hand-written stubs ────────────────────────────────────────────────────

    /** Stub CustomerDAO backed by an in-memory map. */
    static class StubCustomerDAO implements CustomerDAO {
        final Map<String, Customer> store = new LinkedHashMap<>();

        @Override public Customer save(Customer c) { store.put(c.getId(), c); return c; }
        @Override public Optional<Customer> findById(String id) { return Optional.ofNullable(store.get(id)); }
        @Override public List<Customer> findAll()  { return new ArrayList<>(store.values()); }
        @Override public Customer update(Customer c){ store.put(c.getId(), c); return c; }
        @Override public boolean deleteById(String id){ return store.remove(id) != null; }
        @Override public boolean exists(String id) { return store.containsKey(id); }
        
        @Override 
        public List<String> findByNameAndBirthDay(String firstName, String lastName, Date birthDay) {
            return store.values().stream()
                .filter(c -> c.getFirstName().equals(firstName) && 
                             c.getLastName().equals(lastName) && 
                             c.getBirthDay().equals(birthDay))
                .map(Customer::getId)
                .collect(java.util.stream.Collectors.toList());
        }
    }

    /** Stub AccountDAO backed by an in-memory map. */
    static class StubAccountDAO implements AccountDAO {
        final Map<String, Account> store = new LinkedHashMap<>();

        private Account cloneAccount(Account a) {
            if (a == null) return null;
            if (a instanceof PersonalAccount) {
                return new PersonalAccount(a.getId(), a.getBalance(), a.getCustomerId());
            } else {
                return new CorporateAccount(a.getId(), a.getBalance(), a.getCustomerId());
            }
        }

        @Override public Account save(Account a) { 
            store.put(a.getId(), cloneAccount(a)); 
            return cloneAccount(a); 
        }
        @Override public Optional<Account> findById(String id) { 
            return Optional.ofNullable(cloneAccount(store.get(id))); 
        }
        @Override public List<Account> findAll()  { 
            return store.values().stream().map(this::cloneAccount)
                .collect(java.util.stream.Collectors.toList()); 
        }
        @Override public Account update(Account a){ 
            store.put(a.getId(), cloneAccount(a)); 
            return cloneAccount(a); 
        }
        @Override public boolean deleteById(String id){ return store.remove(id) != null; }
        
        @Override
        public List<String> findByCustomerId(String customerId) {
            return store.values().stream()
                .filter(a -> customerId.equals(a.getCustomerId()))
                .map(Account::getId)
                .collect(java.util.stream.Collectors.toList());
        }

        @Override
        public BigDecimal getTotalBalanceByCustomerId(String customerId) {
            return store.values().stream()
                .filter(a -> customerId.equals(a.getCustomerId()))
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public boolean updateBalance(String accountId, BigDecimal newBalance) {
            Optional<Account> acc = findById(accountId);
            if (acc.isPresent()) {
                acc.get().setBalance(newBalance);
                return true;
            }
            return false;
        }
    }

    /**
     * Stub TransactionService that operates entirely in memory on the
     * StubAccountDAO — no database connection needed.
     */
    static class StubTransactionService extends TransactionService {

        private final StubAccountDAO accountDAO;
        // simple audit log to verify calls
        final List<String> auditLog = new ArrayList<>();

        StubTransactionService(StubAccountDAO dao) {
            super(new NoopConnectionManager());
            this.accountDAO = dao;
        }

        @Override
        public boolean recordDeposit(String accountId, BigDecimal amount) {
            Optional<Account> opt = accountDAO.findById(accountId);
            if (opt.isEmpty()) return false;
            Account acc = opt.get();
            acc.setBalance(acc.getBalance().add(amount));
            accountDAO.update(acc);
            auditLog.add("DEPOSIT:" + accountId + ":" + amount);
            return true;
        }

        @Override
        public boolean recordWithdrawal(String accountId, BigDecimal amount,
                                        boolean allowNegative) {
            Optional<Account> opt = accountDAO.findById(accountId);
            if (opt.isEmpty()) return false;
            Account acc = opt.get();
            if (!allowNegative && acc.getBalance().compareTo(amount) < 0) return false;
            acc.setBalance(acc.getBalance().subtract(amount));
            accountDAO.update(acc);
            auditLog.add("WITHDRAWAL:" + accountId + ":" + amount);
            return true;
        }

        @Override
        public boolean recordTransfer(String fromId, String toId,
                                      BigDecimal amount, boolean allowNegative) {
            if (!recordWithdrawal(fromId, amount, allowNegative)) return false;
            Optional<Account> toOpt = accountDAO.findById(toId);
            if (toOpt.isEmpty()) {
                recordDeposit(fromId, amount); // rollback
                return false;
            }
            Account to = toOpt.get();
            to.setBalance(to.getBalance().add(amount));
            accountDAO.update(to);
            auditLog.add("TRANSFER:" + fromId + "->" + toId + ":" + amount);
            return true;
        }

        @Override
        public boolean recordAccountOwnership(String accountId, String[] customerIds) {
            auditLog.add("OWNERSHIP:" + accountId);
            return true;
        }
    }

    /** ConnectionManager stub that returns null — never used since we override all methods in StubTransactionService. */
    static class NoopConnectionManager extends DatabaseConnectionManager {
        NoopConnectionManager() { super(false); } // skip buildDataSource
        @Override public Connection getConnection() { return null; }
        @Override public void releaseConnection(Connection c) {}
        @Override public void closeAllConnections() {}
    }

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private StubCustomerDAO    customerDAO;
    private StubAccountDAO     accountDAO;
    private StubTransactionService txService;
    private Bank bank;

    @BeforeEach
    void setUp() {
        customerDAO = new StubCustomerDAO();
        accountDAO  = new StubAccountDAO();
        txService   = new StubTransactionService(accountDAO);
        bank        = new Bank(customerDAO, accountDAO, txService);
    }

    // ── registerCustomer ──────────────────────────────────────────────────────

    @Test
    @DisplayName("registerCustomer returns a UUID-formatted ID")
    void registerCustomer_validInput_returnsUUID() {
        String id = bank.registerCustomer("Alice", "Smith", new Date());
        assertNotNull(id);
        assertTrue(id.matches("[0-9a-f\\-]{36}"), "Expected UUID, got: " + id);
    }

    @Test
    @DisplayName("registerCustomer persists the customer")
    void registerCustomer_persists() {
        String id = bank.registerCustomer("Bob", "Jones", new Date());
        assertTrue(customerDAO.exists(id));
    }

    @Test
    @DisplayName("registerCustomer returns different IDs for different customers")
    void registerCustomer_uniqueIds() {
        String id1 = bank.registerCustomer("Alice", "Smith", new Date());
        String id2 = bank.registerCustomer("Bob",   "Jones", new Date());
        assertNotEquals(id1, id2);
    }

    // ── registerPersonalAccount ───────────────────────────────────────────────

    @Test
    @DisplayName("registerPersonalAccount returns ID with 'P-' prefix when customer exists")
    void registerPersonalAccount_customerExists_returnsId() {
        String custId = bank.registerCustomer("Alice", "Smith", new Date());
        Optional<String> acc = bank.registerPersonalAccount(custId);
        assertTrue(acc.isPresent());
        assertTrue(acc.get().startsWith("P-"));
    }

    @Test
    @DisplayName("registerPersonalAccount returns empty when customer does not exist")
    void registerPersonalAccount_unknownCustomer_empty() {
        Optional<String> acc = bank.registerPersonalAccount("ghost");
        assertFalse(acc.isPresent());
    }

    // ── registerCorporateAccount ──────────────────────────────────────────────

    @Test
    @DisplayName("registerCorporateAccount returns ID with 'C-' prefix when all owners exist")
    void registerCorporateAccount_allExist_returnsId() {
        String c1 = bank.registerCustomer("Alice", "Smith", new Date());
        String c2 = bank.registerCustomer("Bob",   "Jones", new Date());
        Optional<String> acc = bank.registerCorporateAccount(new String[]{c1, c2});
        assertTrue(acc.isPresent());
        assertTrue(acc.get().startsWith("C-"));
    }

    @Test
    @DisplayName("registerCorporateAccount returns empty when any owner is missing")
    void registerCorporateAccount_missingOwner_empty() {
        String c1 = bank.registerCustomer("Alice", "Smith", new Date());
        Optional<String> acc = bank.registerCorporateAccount(new String[]{c1, "missing"});
        assertFalse(acc.isPresent());
    }

    // ── getBalance ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getBalance returns zero for a newly opened account")
    void getBalance_newAccount_returnsZero() {
        String custId = bank.registerCustomer("Alice", "Smith", new Date());
        Optional<String> accId = bank.registerPersonalAccount(custId);
        assertTrue(accId.isPresent());
        Optional<BigDecimal> bal = bank.getBalance(accId.get());
        assertTrue(bal.isPresent());
        assertEquals(BigDecimal.ZERO, bal.get());
    }

    @Test
    @DisplayName("getBalance returns empty for unknown account")
    void getBalance_unknownAccount_empty() {
        assertFalse(bank.getBalance("no-such-acc").isPresent());
    }

    // ── deposit ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deposit increases account balance")
    void deposit_increasesBalance() {
        String custId = bank.registerCustomer("Alice", "Smith", new Date());
        String accId  = bank.registerPersonalAccount(custId).get();

        assertTrue(bank.deposit(accId, new BigDecimal("100.00")));
        assertEquals(new BigDecimal("100.00"), bank.getBalance(accId).get());
    }

    @Test
    @DisplayName("deposit returns false for null amount")
    void deposit_nullAmount_returnsFalse() {
        assertFalse(bank.deposit("any", null));
    }

    @Test
    @DisplayName("deposit returns false for zero amount")
    void deposit_zeroAmount_returnsFalse() {
        assertFalse(bank.deposit("any", BigDecimal.ZERO));
    }

    @Test
    @DisplayName("deposit returns false for negative amount")
    void deposit_negativeAmount_returnsFalse() {
        assertFalse(bank.deposit("any", new BigDecimal("-1")));
    }

    // ── withdraw ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("withdraw decreases balance when sufficient funds")
    void withdraw_sufficientFunds_succeeds() {
        String custId = bank.registerCustomer("Alice", "Smith", new Date());
        String accId  = bank.registerPersonalAccount(custId).get();
        bank.deposit(accId, new BigDecimal("200.00"));

        assertTrue(bank.withdraw(accId, new BigDecimal("80.00")));
        assertEquals(new BigDecimal("120.00"), bank.getBalance(accId).get());
    }

    @Test
    @DisplayName("withdraw returns false when insufficient funds (personal account)")
    void withdraw_insufficientFunds_returnsFalse() {
        String custId = bank.registerCustomer("Alice", "Smith", new Date());
        String accId  = bank.registerPersonalAccount(custId).get();
        bank.deposit(accId, new BigDecimal("10.00"));

        assertFalse(bank.withdraw(accId, new BigDecimal("100.00")));
        assertEquals(new BigDecimal("10.00"), bank.getBalance(accId).get());
    }

    @Test
    @DisplayName("withdraw returns false for unknown account")
    void withdraw_unknownAccount_returnsFalse() {
        assertFalse(bank.withdraw("ghost", new BigDecimal("50.00")));
    }

    // ── transfer ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("transfer moves money from one account to another")
    void transfer_success() {
        String c1 = bank.registerCustomer("Alice", "Smith", new Date());
        String c2 = bank.registerCustomer("Bob",   "Jones", new Date());
        String a1 = bank.registerPersonalAccount(c1).get();
        String a2 = bank.registerPersonalAccount(c2).get();
        bank.deposit(a1, new BigDecimal("500.00"));

        assertTrue(bank.transfer(a1, a2, new BigDecimal("200.00")));
        assertEquals(new BigDecimal("300.00"), bank.getBalance(a1).get());
        assertEquals(new BigDecimal("200.00"), bank.getBalance(a2).get());
    }

    @Test
    @DisplayName("transfer returns false on insufficient funds and leaves both balances unchanged")
    void transfer_insufficientFunds_rollsBack() {
        String c1 = bank.registerCustomer("Alice", "Smith", new Date());
        String c2 = bank.registerCustomer("Bob",   "Jones", new Date());
        String a1 = bank.registerPersonalAccount(c1).get();
        String a2 = bank.registerPersonalAccount(c2).get();
        bank.deposit(a1, new BigDecimal("10.00"));

        assertFalse(bank.transfer(a1, a2, new BigDecimal("9999.00")));
        assertEquals(new BigDecimal("10.00"), bank.getBalance(a1).get());
        assertEquals(BigDecimal.ZERO,         bank.getBalance(a2).get());
    }
}
