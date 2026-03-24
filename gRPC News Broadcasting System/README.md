# 📡 gRPC News Broadcasting System

![Java](https://img.shields.io/badge/Java-11%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![gRPC](https://img.shields.io/badge/gRPC-Framework-23758F?style=for-the-badge&logo=grpc&logoColor=white)
![Protobuf](https://img.shields.io/badge/Protocol%20Buffers-Data-084B8A?style=for-the-badge)
![Maven](https://img.shields.io/badge/Maven-Build-C71A22?style=for-the-badge&logo=apachemaven&logoColor=white)

A high-performance, real-time news broadcasting system built on the **Publish-Subscribe (Pub/Sub) architectural pattern** using **gRPC** and **Protocol Buffers**. This application acts as a robust message broker, routing topic-based news streams from authenticated publishers to connected clients.

## 🌟 Key Features

* **Real-time Streaming:** Leverages gRPC server-streaming to push news instantly to clients without polling.
* **Topic-based Routing:** Clients subscribe to specific news topics, and the broker intelligently routes messages only to interested parties.
* **Thread-Safe Architecture:** Built from the ground up to support high concurrency using `ConcurrentHashMap` and `CopyOnWriteArrayList`.
* **Security & Content Moderation:**
  * **Trusted Source Manager:** Authenticates publishers before allowing them to broadcast.
  * **Content Filter mechanism:** Automatically scrubs or blocks messages containing restricted keywords.
* **Resilient Client Management:** Automatically detects disconnected gRPC streams and performs memory cleanup to prevent memory leaks.

## 🏗️ Architecture

The system follows the **Observer Design Pattern** adapted for a distributed environment:

```text
[ Trusted Sources (Publishers) ] 
       │ (gRPC Unary calls)
       ▼
┌─────────────────────────────────┐
│     gRPC News Service Node      │
│                                 │
│  ├─ Content Filter              │
│  ├─ Trusted Source Manager      │
│  └─ Main Spreader (Broker)      │
└─────────────────────────────────┘
       │ (gRPC Server Streaming)
       ▼
[ Subscribed Clients (Observers) ]
```

1. **Publishers** send news articles via unary gRPC calls.
2. The **Content Filter** validates and redacts the message.
3. The **Trusted Source Manager** verifies publisher credentials.
4. The **Main Spreader** (acting as the subject) iterates over its thread-safe registry of active **NewsObservers** and streams the payload.

## 🚀 Getting Started

### Prerequisites
* Java 11 or higher
* Maven 3.6+

### Build & Run
1. **Clone the repository and build the project:**
   ```bash
   mvn clean package
   ```
2. **Start the gRPC Server:**
   ```bash
   mvn exec:java -Dexec.mainClass="observer.ServerMain"
   ```
   *The server will start and bind to port `50051`. A JVM shutdown hook ensures graceful termination.*

### Running the Test Suite
The project features a comprehensive JUnit 5 test suite validating topic routing, content blocking, and concurrency.
```bash
mvn test
```

## 🛠️ Tech Stack & Design Decisions
* **gRPC / HTTP/2:** Chosen for its low latency, high throughput, and native streaming capabilities compared to REST/HTTP 1.1.
* **Protocol Buffers:** Ensures strict type safety, backward compatibility, and highly compressed payload sizes over the wire.
* **Concurrency:** Replaced legacy locks with `java.util.concurrent` non-blocking collections to eliminate thread bottlenecks during mass broadcasts.
