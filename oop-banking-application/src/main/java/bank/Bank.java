package bank;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import bank.database.*;

/**
 * Manages banking operations including customer and account management.
 * This version integrates with a database for persistent storage.
 */
public class Bank {
    // These maps now serve as a cache to reduce database queries
    private Map<String, Customer> customerCache = new HashMap<>();
    private Map<String, Account> accountCache = new HashMap<>();
    
    // DAOs for database access
    private CustomerDAO customerDAO;
    private AccountDAO accountDAO;
    private TransactionService transactionService;
    
    public Bank() {
        // Initialize DAOs
        this.customerDAO = new CustomerDAOImpl();
        this.accountDAO = new AccountDAOImpl();
        this.transactionService = new TransactionService();
        
        // Optionally preload cache for better performance
        preloadCache();
    }

    /**
     * Package-private constructor for dependency injection (used in tests).
     * Allows injecting mock DAOs without touching the database.
     */
    Bank(CustomerDAO customerDAO, AccountDAO accountDAO, TransactionService transactionService) {
        this.customerDAO = customerDAO;
        this.accountDAO = accountDAO;
        this.transactionService = transactionService;
    }

    
    /**
     * Preloads the cache with data from the database for faster access.
     */
    private void preloadCache() {
        // Load customers to cache
        customerDAO.findAll().forEach(customer -> 
            customerCache.put(customer.getId(), customer));
            
        // Load accounts to cache
        accountDAO.findAll().forEach(account -> 
            accountCache.put(account.getId(), account));
    }
    
    /**
     * Registers a customer and returns a unique customer ID.
     */
    public String registerCustomer(String firstName, String lastName, Date birthDay) {
        String id = UUID.randomUUID().toString();  // Collision-proof unique ID
        Customer newCustomer = new Customer(id, firstName, lastName, birthDay);
        
        // Save to database
        customerDAO.save(newCustomer);
        
        // Update cache
        customerCache.put(id, newCustomer);
        
        return id;
    }

    /**
     * Retrieves customer IDs based on name and birthday.
     */
    public Collection<String> getCustomers(String firstName, String lastName, Date birthDay) {
        // Query database directly rather than using cache for this operation
        return customerDAO.findByNameAndBirthDay(firstName, lastName, birthDay);
    }

    /**
     * Removes an account by ID.
     */
    public boolean removeAccount(String accountId) {
        boolean removed = accountDAO.deleteById(accountId);
        
        // Update cache if successful
        if (removed) {
            accountCache.remove(accountId);
        }
        
        return removed;
    }

    /**
     * Registers a corporate account for multiple customers.
     */
    public Optional<String> registerCorporateAccount(String[] customerData) {
        // Verify all customers exist
        for (String customerId : customerData) {
            if (!customerDAO.exists(customerId)) {
                return Optional.empty();  // Return empty if any customer ID is invalid
            }
        }
        
        String accountId = "C-" + UUID.randomUUID().toString();  // Collision-proof unique ID
        CorporateAccount newAccount = new CorporateAccount(accountId, BigDecimal.ZERO, customerData[0]);
        
        // Save to database
        accountDAO.save(newAccount);
        
        // For corporate accounts, record ownership information
        transactionService.recordAccountOwnership(accountId, customerData);
        
        // Update cache
        accountCache.put(accountId, newAccount);
        
        return Optional.of(accountId);
    }

    /**
     * Registers a personal account for a single customer.
     */
    public Optional<String> registerPersonalAccount(String customerId) {
        if (customerDAO.exists(customerId)) {
            String accountId = "P-" + UUID.randomUUID().toString();  // Collision-proof unique ID
            PersonalAccount newAccount = new PersonalAccount(accountId, BigDecimal.ZERO, customerId);
            
            // Save to database
            accountDAO.save(newAccount);
            
            // Update cache
            accountCache.put(accountId, newAccount);
            
            return Optional.of(accountId);
        }
        return Optional.empty();
    }

    /**
     * Retrieves the balance for a specified account.
     */
    public Optional<BigDecimal> getBalance(String accountId) {
        // Try cache first
        if (accountCache.containsKey(accountId)) {
            return Optional.of(accountCache.get(accountId).getBalance());
        }
        
        // If not in cache, try database
        Optional<Account> account = accountDAO.findById(accountId);
        account.ifPresent(acc -> accountCache.put(accountId, acc));
        
        return account.map(Account::getBalance);
    }

    /**
     * Deposits an amount to a specific account.
     */
    public boolean deposit(String accountId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;  // Validation failed
        }
        
        // Use transaction service to ensure ACID properties
        boolean success = transactionService.recordDeposit(accountId, amount);
        
        if (success) {
            // Update cache if account exists in cache
            if (accountCache.containsKey(accountId)) {
                Account account = accountCache.get(accountId);
                account.deposit(amount);
            } else {
                // Refresh account from database
                Optional<Account> account = accountDAO.findById(accountId);
                account.ifPresent(acc -> accountCache.put(accountId, acc));
            }
        }
        
        return success;
    }

    /**
     * Withdraws an amount from a specific account.
     */
    public boolean withdraw(String accountId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;  // Validation failed
        }
        
        // Determine if this is a corporate account (allows negative balance)
        boolean isCorporate = false;
        if (accountCache.containsKey(accountId)) {
            isCorporate = accountCache.get(accountId) instanceof CorporateAccount;
        } else {
            Optional<Account> account = accountDAO.findById(accountId);
            if (account.isPresent()) {
                isCorporate = account.get() instanceof CorporateAccount;
                accountCache.put(accountId, account.get());
            } else {
                return false;  // Account not found
            }
        }
        
        // Use transaction service to ensure ACID properties
        boolean success = transactionService.recordWithdrawal(accountId, amount, isCorporate);
        
        if (success) {
            // Update cache if account exists in cache
            if (accountCache.containsKey(accountId)) {
                Account account = accountCache.get(accountId);
                account.withdraw(amount);
            } else {
                // Refresh account from database
                Optional<Account> account = accountDAO.findById(accountId);
                account.ifPresent(acc -> accountCache.put(accountId, acc));
            }
        }
        
        return success;
    }

    /**
     * Transfers an amount from one account to another.
     */
    public boolean transfer(String fromAccountId, String toAccountId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;  // Validation failed
        }
        
        // Determine if the source account is corporate (allows negative balance)
        boolean isCorporate = false;
        if (accountCache.containsKey(fromAccountId)) {
            isCorporate = accountCache.get(fromAccountId) instanceof CorporateAccount;
        } else {
            Optional<Account> account = accountDAO.findById(fromAccountId);
            if (account.isPresent()) {
                isCorporate = account.get() instanceof CorporateAccount;
                accountCache.put(fromAccountId, account.get());
            } else {
                return false;  // Account not found
            }
        }
        
        // Verify destination account exists
        if (!accountCache.containsKey(toAccountId) && !accountDAO.findById(toAccountId).isPresent()) {
            return false;  // Destination account not found
        }
        
        // Use transaction service to ensure ACID properties
        boolean success = transactionService.recordTransfer(fromAccountId, toAccountId, amount, isCorporate);
        
        if (success) {
            // Update cache for source account
            if (accountCache.containsKey(fromAccountId)) {
                Account account = accountCache.get(fromAccountId);
                account.withdraw(amount);
            }
            
            // Update cache for destination account
            if (accountCache.containsKey(toAccountId)) {
                Account account = accountCache.get(toAccountId);
                account.deposit(amount);
            }
        }
        
        return success;
    }

    /**
     * Retrieves a list of all account IDs for a given customer.
     */
    public Optional<Collection<String>> getAccounts(String customerId) {
        if (!customerDAO.exists(customerId)) {
            return Optional.empty();
        }
        
        List<String> accountIds = accountDAO.findByCustomerId(customerId);
        return Optional.of(accountIds);
    }

    /**
     * Calculates the total balance of all accounts owned by a customer.
     */
    public Optional<BigDecimal> getTotalBalance(String customerId) {
        if (!customerDAO.exists(customerId)) {
            return Optional.empty();
        }
        
        BigDecimal totalBalance = accountDAO.getTotalBalanceByCustomerId(customerId);
        return Optional.of(totalBalance);
    }
    
    /**
     * Gets transaction history for an account.
     * 
     * @param accountId The account ID
     * @return List of transaction details
     */
    public List<Map<String, Object>> getTransactionHistory(String accountId) {
        return transactionService.getTransactionHistory(accountId);
    }
    
    /**
     * Gets account statement for a specified period.
     * 
     * @param accountId The account ID
     * @param startDate Start date of the statement period
     * @param endDate End date of the statement period
     * @return Map containing statement details
     */
    public Map<String, Object> getAccountStatement(String accountId, Date startDate, Date endDate) {
        return transactionService.getAccountStatement(accountId, startDate, endDate);
    }
    
    /**
     * Updates customer information.
     * 
     * @param customerId The customer ID
     * @param firstName New first name
     * @param lastName New last name
     * @param birthDay New birth date
     * @return true if updated successfully
     */
    public boolean updateCustomer(String customerId, String firstName, String lastName, Date birthDay) {
        Optional<Customer> existingCustomer = customerDAO.findById(customerId);
        
        if (!existingCustomer.isPresent()) {
            return false;  // Customer not found
        }
        
        Customer customer = existingCustomer.get();
        Customer updatedCustomer = new Customer(customerId, firstName, lastName, birthDay);
        
        // Update database
        customerDAO.update(updatedCustomer);
        
        // Update cache
        customerCache.put(customerId, updatedCustomer);
        
        return true;
    }
    
    /**
     * Closes the database connections when the application shuts down.
     */
    public void shutdown() {
        DatabaseConnectionManager.getInstance().closeAllConnections();
    }
}