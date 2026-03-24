package bank.database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service class for handling banking transactions with proper transaction management.
 */
public class TransactionService {
    
    private DatabaseConnectionManager connectionManager;
    
    public TransactionService() {
        this.connectionManager = DatabaseConnectionManager.getInstance();
    }

    /**
     * Public constructor for testing — allows injecting a custom
     * (e.g. H2-backed) connection manager without touching the singleton.
     */
    public TransactionService(DatabaseConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }


    
    /**
     * Records a deposit transaction and updates account balance.
     * @param accountId The account ID
     * @param amount The amount to deposit
     * @return true if successful
     */
    public boolean recordDeposit(String accountId, BigDecimal amount) {
        Connection connection = connectionManager.getConnection();
        try {
            connection.setAutoCommit(false);
            
            // First, update the account balance
            String updateSql = "UPDATE accounts SET balance = balance + ? WHERE id = ?";
            try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                updateStmt.setBigDecimal(1, amount);
                updateStmt.setString(2, accountId);
                
                int rowsAffected = updateStmt.executeUpdate();
                if (rowsAffected == 0) {
                    connection.rollback();
                    return false;
                }
            }
            
            // Then, record the transaction
            String insertSql = "INSERT INTO transactions (account_id, transaction_type, amount) VALUES (?, 'DEPOSIT', ?)";
            try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                insertStmt.setString(1, accountId);
                insertStmt.setBigDecimal(2, amount);
                
                insertStmt.executeUpdate();
            }
            
            // Finally, add an audit log entry
            String auditSql = "INSERT INTO audit_log (action_type, entity_type, entity_id, description) VALUES (?, ?, ?, ?)";
            try (PreparedStatement auditStmt = connection.prepareStatement(auditSql)) {
                auditStmt.setString(1, "DEPOSIT");
                auditStmt.setString(2, "ACCOUNT");
                auditStmt.setString(3, accountId);
                auditStmt.setString(4, "Deposit of " + amount);
                
                auditStmt.executeUpdate();
            }
            
            connection.commit();
            return true;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                throw new DatabaseException("Error rolling back transaction", rollbackEx);
            }
            throw new DatabaseException("Error recording deposit: " + e.getMessage(), e);
        } finally {
            try {
                connection.setAutoCommit(true);
                connectionManager.releaseConnection(connection);
            } catch (SQLException e) {
                throw new DatabaseException("Error resetting auto-commit", e);
            }
        }
    }
    
    /**
     * Records a withdrawal transaction and updates account balance.
     * @param accountId The account ID
     * @param amount The amount to withdraw
     * @param allowNegativeBalance Whether to allow negative balance (for corporate accounts)
     * @return true if successful
     */
    public boolean recordWithdrawal(String accountId, BigDecimal amount, boolean allowNegativeBalance) {
        Connection connection = connectionManager.getConnection();
        try {
            connection.setAutoCommit(false);
            
            // Check current balance if negative balance is not allowed
            if (!allowNegativeBalance) {
                String checkSql = "SELECT balance FROM accounts WHERE id = ?";
                try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
                    checkStmt.setString(1, accountId);
                    
                    ResultSet rs = checkStmt.executeQuery();
                    if (rs.next()) {
                        BigDecimal currentBalance = rs.getBigDecimal("balance");
                        if (currentBalance.compareTo(amount) < 0) {
                            connection.rollback();
                            return false; // Insufficient funds
                        }
                    } else {
                        connection.rollback();
                        return false; // Account not found
                    }
                }
            }
            
            // Update account balance
            String updateSql = "UPDATE accounts SET balance = balance - ? WHERE id = ?";
            try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                updateStmt.setBigDecimal(1, amount);
                updateStmt.setString(2, accountId);
                
                int rowsAffected = updateStmt.executeUpdate();
                if (rowsAffected == 0) {
                    connection.rollback();
                    return false;
                }
            }
            
            // Record the transaction
            String insertSql = "INSERT INTO transactions (account_id, transaction_type, amount) VALUES (?, 'WITHDRAWAL', ?)";
            try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                insertStmt.setString(1, accountId);
                insertStmt.setBigDecimal(2, amount);
                
                insertStmt.executeUpdate();
            }
            
            // Add an audit log entry
            String auditSql = "INSERT INTO audit_log (action_type, entity_type, entity_id, description) VALUES (?, ?, ?, ?)";
            try (PreparedStatement auditStmt = connection.prepareStatement(auditSql)) {
                auditStmt.setString(1, "WITHDRAWAL");
                auditStmt.setString(2, "ACCOUNT");
                auditStmt.setString(3, accountId);
                auditStmt.setString(4, "Withdrawal of " + amount);
                
                auditStmt.executeUpdate();
            }
            
            connection.commit();
            return true;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                throw new DatabaseException("Error rolling back transaction", rollbackEx);
            }
            throw new DatabaseException("Error recording withdrawal: " + e.getMessage(), e);
        } finally {
            try {
                connection.setAutoCommit(true);
                connectionManager.releaseConnection(connection);
            } catch (SQLException e) {
                throw new DatabaseException("Error resetting auto-commit", e);
            }
        }
    }
    
    /**
     * Records a transfer between two accounts with transaction management.
     * @param fromAccountId Source account ID
     * @param toAccountId Destination account ID
     * @param amount Amount to transfer
     * @param allowNegativeBalance Whether to allow negative balance in source account
     * @return true if successful
     */
    public boolean recordTransfer(String fromAccountId, String toAccountId, BigDecimal amount, boolean allowNegativeBalance) {
        Connection connection = connectionManager.getConnection();
        try {
            connection.setAutoCommit(false);
            
            // Check current balance if negative balance is not allowed
            if (!allowNegativeBalance) {
                String checkSql = "SELECT balance FROM accounts WHERE id = ?";
                try (PreparedStatement checkStmt = connection.prepareStatement(checkSql)) {
                    checkStmt.setString(1, fromAccountId);
                    
                    ResultSet rs = checkStmt.executeQuery();
                    if (rs.next()) {
                        BigDecimal currentBalance = rs.getBigDecimal("balance");
                        if (currentBalance.compareTo(amount) < 0) {
                            connection.rollback();
                            return false; // Insufficient funds
                        }
                    } else {
                        connection.rollback();
                        return false; // Account not found
                    }
                }
            }
            
            // Update source account balance
            String updateSourceSql = "UPDATE accounts SET balance = balance - ? WHERE id = ?";
            try (PreparedStatement updateStmt = connection.prepareStatement(updateSourceSql)) {
                updateStmt.setBigDecimal(1, amount);
                updateStmt.setString(2, fromAccountId);
                
                int rowsAffected = updateStmt.executeUpdate();
                if (rowsAffected == 0) {
                    connection.rollback();
                    return false;
                }
            }
            
            // Update destination account balance
            String updateDestSql = "UPDATE accounts SET balance = balance + ? WHERE id = ?";
            try (PreparedStatement updateStmt = connection.prepareStatement(updateDestSql)) {
                updateStmt.setBigDecimal(1, amount);
                updateStmt.setString(2, toAccountId);
                
                int rowsAffected = updateStmt.executeUpdate();
                if (rowsAffected == 0) {
                    connection.rollback();
                    return false;
                }
            }
            
            // Record withdrawal transaction
            String insertWithdrawalSql = "INSERT INTO transactions (account_id, transaction_type, amount, related_account_id) VALUES (?, 'TRANSFER_OUT', ?, ?)";
            try (PreparedStatement insertStmt = connection.prepareStatement(insertWithdrawalSql)) {
                insertStmt.setString(1, fromAccountId);
                insertStmt.setBigDecimal(2, amount);
                insertStmt.setString(3, toAccountId);
                
                insertStmt.executeUpdate();
            }
            
            // Record deposit transaction
            String insertDepositSql = "INSERT INTO transactions (account_id, transaction_type, amount, related_account_id) VALUES (?, 'TRANSFER_IN', ?, ?)";
            try (PreparedStatement insertStmt = connection.prepareStatement(insertDepositSql)) {
                insertStmt.setString(1, toAccountId);
                insertStmt.setBigDecimal(2, amount);
                insertStmt.setString(3, fromAccountId);
                
                insertStmt.executeUpdate();
            }
            
            // Add an audit log entry
            String auditSql = "INSERT INTO audit_log (action_type, entity_type, entity_id, description) VALUES (?, ?, ?, ?)";
            try (PreparedStatement auditStmt = connection.prepareStatement(auditSql)) {
                auditStmt.setString(1, "TRANSFER");
                auditStmt.setString(2, "ACCOUNT");
                auditStmt.setString(3, fromAccountId + "," + toAccountId);
                auditStmt.setString(4, "Transfer of " + amount + " from " + fromAccountId + " to " + toAccountId);
                
                auditStmt.executeUpdate();
            }
            
            connection.commit();
            return true;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                throw new DatabaseException("Error rolling back transaction", rollbackEx);
            }
            throw new DatabaseException("Error recording transfer: " + e.getMessage(), e);
        } finally {
            try {
                connection.setAutoCommit(true);
                connectionManager.releaseConnection(connection);
            } catch (SQLException e) {
                throw new DatabaseException("Error resetting auto-commit", e);
            }
        }
    }
    
    /**
     * Gets transaction history for an account.
     * @param accountId The account ID
     * @return List of transaction details as maps
     */
    public List<Map<String, Object>> getTransactionHistory(String accountId) {
        String sql = "SELECT * FROM transactions WHERE account_id = ? ORDER BY transaction_date DESC";
        List<Map<String, Object>> transactions = new ArrayList<>();
        
        Connection connection = connectionManager.getConnection();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, accountId);
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> transaction = new HashMap<>();
                transaction.put("id", rs.getLong("id"));
                transaction.put("accountId", rs.getString("account_id"));
                transaction.put("type", rs.getString("transaction_type"));
                transaction.put("amount", rs.getBigDecimal("amount"));
                transaction.put("relatedAccountId", rs.getString("related_account_id"));
                transaction.put("date", rs.getTimestamp("transaction_date"));
                
                transactions.add(transaction);
            }
            
            return transactions;
        } catch (SQLException e) {
            throw new DatabaseException("Error retrieving transaction history: " + e.getMessage(), e);
        } finally {
            connectionManager.releaseConnection(connection);
        }
    }
    
    /**
     * Gets the account statement for a specific time period.
     * @param accountId The account ID
     * @param startDate Start date of the statement period
     * @param endDate End date of the statement period
     * @return Transaction history and summary
     */
    public Map<String, Object> getAccountStatement(String accountId, Date startDate, Date endDate) {
        Map<String, Object> statement = new HashMap<>();
        
        String transactionSql = "SELECT * FROM transactions WHERE account_id = ? AND " +
                                "transaction_date BETWEEN ? AND ? ORDER BY transaction_date";
                                
        String balanceSql = "SELECT balance FROM accounts WHERE id = ?";
        
        Connection connection = connectionManager.getConnection();
        try {
            // Get current balance
            BigDecimal currentBalance = BigDecimal.ZERO;
            try (PreparedStatement balanceStmt = connection.prepareStatement(balanceSql)) {
                balanceStmt.setString(1, accountId);
                
                ResultSet balanceRs = balanceStmt.executeQuery();
                if (balanceRs.next()) {
                    currentBalance = balanceRs.getBigDecimal("balance");
                }
            }
            
            // Get transactions
            List<Map<String, Object>> transactions = new ArrayList<>();
            BigDecimal totalDeposits = BigDecimal.ZERO;
            BigDecimal totalWithdrawals = BigDecimal.ZERO;
            
            try (PreparedStatement transactionStmt = connection.prepareStatement(transactionSql)) {
                transactionStmt.setString(1, accountId);
                transactionStmt.setTimestamp(2, new java.sql.Timestamp(startDate.getTime()));
                transactionStmt.setTimestamp(3, new java.sql.Timestamp(endDate.getTime()));
                
                ResultSet rs = transactionStmt.executeQuery();
                while (rs.next()) {
                    Map<String, Object> transaction = new HashMap<>();
                    transaction.put("id", rs.getLong("id"));
                    transaction.put("type", rs.getString("transaction_type"));
                    transaction.put("amount", rs.getBigDecimal("amount"));
                    transaction.put("relatedAccountId", rs.getString("related_account_id"));
                    transaction.put("date", rs.getTimestamp("transaction_date"));
                    
                    transactions.add(transaction);
                    
                    // Calculate totals
                    BigDecimal amount = rs.getBigDecimal("amount");
                    String type = rs.getString("transaction_type");
                    if ("DEPOSIT".equals(type) || "TRANSFER_IN".equals(type)) {
                        totalDeposits = totalDeposits.add(amount);
                    } else if ("WITHDRAWAL".equals(type) || "TRANSFER_OUT".equals(type)) {
                        totalWithdrawals = totalWithdrawals.add(amount);
                    }
                }
            }
            
            // Populate statement
            statement.put("accountId", accountId);
            statement.put("startDate", startDate);
            statement.put("endDate", endDate);
            statement.put("transactions", transactions);
            statement.put("currentBalance", currentBalance);
            statement.put("totalDeposits", totalDeposits);
            statement.put("totalWithdrawals", totalWithdrawals);
            statement.put("netChange", totalDeposits.subtract(totalWithdrawals));
            
            return statement;
        } catch (SQLException e) {
            throw new DatabaseException("Error generating account statement: " + e.getMessage(), e);
        } finally {
            connectionManager.releaseConnection(connection);
        }
    }
    
    /**
     * Records account ownership for corporate accounts.
     * @param accountId The account ID
     * @param customerIds Array of customer IDs
     * @return true if successful
     */
    public boolean recordAccountOwnership(String accountId, String[] customerIds) {
        Connection connection = connectionManager.getConnection();
        try {
            connection.setAutoCommit(false);
            
            // First, delete existing ownership records
            String deleteSql = "DELETE FROM account_owners WHERE account_id = ?";
            try (PreparedStatement deleteStmt = connection.prepareStatement(deleteSql)) {
                deleteStmt.setString(1, accountId);
                deleteStmt.executeUpdate();
            }
            
            // Then, insert new ownership records
            String insertSql = "INSERT INTO account_owners (account_id, customer_id) VALUES (?, ?)";
            try (PreparedStatement insertStmt = connection.prepareStatement(insertSql)) {
                for (String customerId : customerIds) {
                    insertStmt.setString(1, accountId);
                    insertStmt.setString(2, customerId);
                    insertStmt.addBatch();
                }
                insertStmt.executeBatch();
            }
            
            // Add audit log entry
            String auditSql = "INSERT INTO audit_log (action_type, entity_type, entity_id, description) VALUES (?, ?, ?, ?)";
            try (PreparedStatement auditStmt = connection.prepareStatement(auditSql)) {
                auditStmt.setString(1, "UPDATE_OWNERSHIP");
                auditStmt.setString(2, "ACCOUNT");
                auditStmt.setString(3, accountId);
                auditStmt.setString(4, "Updated account ownership");
                
                auditStmt.executeUpdate();
            }
            
            connection.commit();
            return true;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                throw new DatabaseException("Error rolling back transaction", rollbackEx);
            }
            throw new DatabaseException("Error recording account ownership: " + e.getMessage(), e);
        } finally {
            try {
                connection.setAutoCommit(true);
                connectionManager.releaseConnection(connection);
            } catch (SQLException e) {
                throw new DatabaseException("Error resetting auto-commit", e);
            }
        }
    }
}