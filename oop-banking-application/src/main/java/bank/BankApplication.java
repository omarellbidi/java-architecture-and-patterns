package bank;

import bank.database.DatabaseInitializer;
import java.io.File;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Main application entry point for the Banking System.
 * Initializes the database and provides an interactive command-line interface.
 */
public class BankApplication {

    private static Bank bank;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    public static void main(String[] args) {
        System.out.println("Initializing Banking System...");

        // Initialize the database schema if the SQL file is present
        File schemaFile = new File("config/schema.sql");
        if (schemaFile.exists()) {
            System.out.println("Initializing database schema...");
            new DatabaseInitializer().initializeSchema(schemaFile.getPath());
        }

        bank = new Bank();
        System.out.println("Banking System initialized successfully!");

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down Banking System...");
            bank.shutdown();
        }, "bank-shutdown"));

        startCommandLineInterface();
    }

    // ── CLI loop ──────────────────────────────────────────────────────────────

    private static void startCommandLineInterface() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("\n╔════════════════════════════════════════╗");
        System.out.println("║   Enterprise Banking System — CLI      ║");
        System.out.println("╚════════════════════════════════════════╝");

        while (true) {
            System.out.println("\n  1. Register Customer");
            System.out.println("  2. Create Personal Account");
            System.out.println("  3. Create Corporate Account");
            System.out.println("  4. Deposit");
            System.out.println("  5. Withdraw");
            System.out.println("  6. Transfer");
            System.out.println("  7. Check Balance");
            System.out.println("  8. View Transaction History");
            System.out.println("  9. Exit");
            System.out.print("\n> Enter command: ");

            String command = scanner.nextLine().trim();

            try {
                switch (command) {
                    case "1": registerCustomer(scanner);          break;
                    case "2": createPersonalAccount(scanner);     break;
                    case "3": createCorporateAccount(scanner);    break;
                    case "4": deposit(scanner);                   break;
                    case "5": withdraw(scanner);                  break;
                    case "6": transfer(scanner);                  break;
                    case "7": checkBalance(scanner);              break;
                    case "8": viewTransactionHistory(scanner);    break;
                    case "9":
                        System.out.println("Goodbye!");
                        scanner.close();
                        return;
                    default:
                        System.out.println("Invalid command. Please enter a number 1-9.");
                }
            } catch (Exception e) {
                System.out.println("[ERROR] " + e.getMessage());
            }
        }
    }

    // ── Individual commands ───────────────────────────────────────────────────

    private static void registerCustomer(Scanner sc) {
        System.out.print("  First name: ");
        String firstName = sc.nextLine().trim();
        System.out.print("  Last name:  ");
        String lastName = sc.nextLine().trim();
        System.out.print("  Birth date (yyyy-MM-dd): ");
        Date birthDay = parseDate(sc.nextLine().trim());

        String id = bank.registerCustomer(firstName, lastName, birthDay);
        System.out.println("✔ Customer registered. ID: " + id);
    }

    private static void createPersonalAccount(Scanner sc) {
        System.out.print("  Customer ID: ");
        String customerId = sc.nextLine().trim();

        Optional<String> accountId = bank.registerPersonalAccount(customerId);
        accountId.ifPresentOrElse(
                id -> System.out.println("✔ Personal account created. Account ID: " + id),
                ()  -> System.out.println("✘ Customer not found: " + customerId)
        );
    }

    private static void createCorporateAccount(Scanner sc) {
        System.out.print("  Customer IDs (comma-separated): ");
        String[] ids = sc.nextLine().trim().split("\\s*,\\s*");

        Optional<String> accountId = bank.registerCorporateAccount(ids);
        accountId.ifPresentOrElse(
                id -> System.out.println("✔ Corporate account created. Account ID: " + id),
                ()  -> System.out.println("✘ One or more customer IDs were invalid.")
        );
    }

    private static void deposit(Scanner sc) {
        System.out.print("  Account ID: ");
        String accountId = sc.nextLine().trim();
        System.out.print("  Amount:     ");
        BigDecimal amount = new BigDecimal(sc.nextLine().trim());

        boolean ok = bank.deposit(accountId, amount);
        System.out.println(ok ? "✔ Deposit successful." : "✘ Deposit failed — account not found.");
    }

    private static void withdraw(Scanner sc) {
        System.out.print("  Account ID: ");
        String accountId = sc.nextLine().trim();
        System.out.print("  Amount:     ");
        BigDecimal amount = new BigDecimal(sc.nextLine().trim());

        boolean ok = bank.withdraw(accountId, amount);
        System.out.println(ok ? "✔ Withdrawal successful."
                              : "✘ Withdrawal failed — insufficient funds or account not found.");
    }

    private static void transfer(Scanner sc) {
        System.out.print("  From account ID: ");
        String fromId = sc.nextLine().trim();
        System.out.print("  To account ID:   ");
        String toId = sc.nextLine().trim();
        System.out.print("  Amount:          ");
        BigDecimal amount = new BigDecimal(sc.nextLine().trim());

        boolean ok = bank.transfer(fromId, toId, amount);
        System.out.println(ok ? "✔ Transfer successful."
                              : "✘ Transfer failed — check account IDs and balance.");
    }

    private static void checkBalance(Scanner sc) {
        System.out.print("  Account ID: ");
        String accountId = sc.nextLine().trim();

        Optional<BigDecimal> balance = bank.getBalance(accountId);
        balance.ifPresentOrElse(
                b -> System.out.printf("  Balance: %,.2f%n", b),
                ()  -> System.out.println("✘ Account not found.")
        );
    }

    private static void viewTransactionHistory(Scanner sc) {
        System.out.print("  Account ID: ");
        String accountId = sc.nextLine().trim();

        List<Map<String, Object>> history = bank.getTransactionHistory(accountId);
        if (history.isEmpty()) {
            System.out.println("  No transactions found.");
            return;
        }
        System.out.printf("  %-6s  %-14s  %-12s  %-26s%n", "ID", "Type", "Amount", "Date");
        System.out.println("  " + "─".repeat(62));
        history.forEach(tx -> System.out.printf("  %-6s  %-14s  %-12.2f  %-26s%n",
                tx.get("id"), tx.get("type"), tx.get("amount"), tx.get("date")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Date parseDate(String input) {
        try {
            return DATE_FORMAT.parse(input);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid date format. Use yyyy-MM-dd. Got: " + input);
        }
    }
}