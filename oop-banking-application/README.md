# Enterprise OOP Banking Application

![Java](https://img.shields.io/badge/Java-11%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-Database-4479A1?style=for-the-badge&logo=mysql&logoColor=white)
![H2](https://img.shields.io/badge/H2-In_Memory_DB-1F4F8D?style=for-the-badge&logo=h2)
![Mockito](https://img.shields.io/badge/Mockito-Testing-538421?style=for-the-badge)
![Maven](https://img.shields.io/badge/Maven-Build-C71A22?style=for-the-badge&logo=apachemaven&logoColor=white)

A robust, enterprise-grade banking application built utilizing **Object-Oriented Programming (OOP) principles** and the **Data Access Object (DAO) design pattern**. It handles registration, accounts (Personal and Corporate), thread-safe transactions, and full database persistence with HikariCP.

## Key Features

* **Multi-Account Support:** Segregates logic for **Personal Accounts** (strict balances) and **Corporate Accounts** (support for multiple owners and negative overdraft balances).
* **ACID-Compliant Transactions:** `TransactionService` enforces Atomicity, Consistency, Isolation, and Durability (ACID) by executing transfers atomically within a SQL transaction, utilizing `Connection.rollback()` on failure.
* **Connection Pooling:** Utilizes `HikariCP` for high-performance and reliable database connection management instead of hand-rolled arrays.
* **Unique Identification:** Employs collision-proof `UUID.randomUUID()` for entities to prevent id clashes at scale.
* **Audit Logging:** Every financial action creates an immutable trail in the `audit_log` database table.
* **Interactive CLI Interface:** Fully functioning command-line interface for the end-user.

## Architecture

The codebase represents a classic layered N-Tier architecture:

```text
       [ End User / CLI ]
               │
               ▼
┌───────────────────────────────┐
│     Application Layer         │
│     (BankApplication)         │
│     (Bank Service core)       │
└──────────────┬────────────────┘
               │
               ▼
┌───────────────────────────────┐
│    Persistence Layer (DAO)    │
│  (CustomerDAO, AccountDAO)    │
│  (TransactionService)         │
└──────────────┬────────────────┘
               │
               ▼
┌───────────────────────────────┐
│ Database Connection Pool      │
│  (HikariCP DataSource)        │
└──────────────┬────────────────┘
               │
               ▼
[ Relational Database (MySQL)   ]
```

## Getting Started

### Prerequisites
* Java 11 or higher
* Maven 3.6+
* MySQL 8.0+ (For production schema)

### Setup & Configuration

1. **Clone and Build the Application:**
   ```bash
   mvn clean package
   ```

2. **Configure Database Connection:**
   Copy the default configuration and update it to point to your MySQL database:
   ```bash
   cp config/database.properties.template config/database.properties
   # Edit config/database.properties with your MySQL credentials
   ```
   *(Note: The database schema is automatically localized and initialized up on starting the app via `config/schema.sql`).*

3. **Run the Application:**
   Start the interactive CLI Banking Client:
   ```bash
   mvn exec:java -Dexec.mainClass="bank.BankApplication"
   ```

### Running the Test Suite
The testing environment avoids database conflicts by utilizing **H2 In-Memory Databases** and **In-Memory Stubs**, meaning it can be run instantly without configuring a MySQL server.
```bash
mvn test
```

## 🛠️ Security Practices & Design Decisions
* Using `PreparedStatement` interfaces universally to prevent arbitrary SQL Injection attacks.
* Graceful Application Shutdown hooks deployed to ensure connections to `HikariCP` pool are cleanly closed whenever the app terminates.
* Separated persistence concern (DAOs) from business logic (Service).
