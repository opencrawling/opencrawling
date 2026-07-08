# OpenCrawling

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat)](LICENSE)
[![Java Version](https://img.shields.io/badge/Java-25-orange.svg?style=flat&logo=openjdk&logoColor=white)](https://jdk.java.net/25/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4+-green.svg?style=flat&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Docker](https://img.shields.io/badge/Docker-Supported-blue.svg?style=flat&logo=docker&logoColor=white)](https://www.docker.com/)
[![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-Supported-black.svg?style=flat&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17+-blue.svg?style=flat&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-Supported-red.svg?style=flat&logo=redis&logoColor=white)](https://redis.io/)
[![GitHub Stars](https://img.shields.io/github/stars/OpenPj/spring-manifold-next-gen.svg?style=flat&logo=github)](https://github.com/OpenPj/spring-manifold-next-gen/stargazers)
[![GitHub Issues](https://img.shields.io/github/issues/OpenPj/spring-manifold-next-gen.svg?style=flat&logo=github)](https://github.com/OpenPj/spring-manifold-next-gen/issues)
[![GitHub PRs](https://img.shields.io/github/issues-pr/OpenPj/spring-manifold-next-gen.svg?style=flat&logo=github)](https://github.com/OpenPj/spring-manifold-next-gen/pulls)


**OpenCrawling** is an enterprise data integration and ingestion platform modeled after Apache ManifoldCF. It leverages modern Java 25 features (such as Structured Concurrency and Virtual Threads), Spring Boot, and vector search infrastructure to orchestrate data flows from various repository connectors to vector search outputs.

<p align="center">
  <img src="images/logo.png" alt="OpenCrawling Logo" width="200" />
</p>

---

## Architecture Diagram

The diagram below shows the high-level architecture of OpenCrawling:

```mermaid
graph TD
    subgraph UI
        UI_App[Admin React UI - oc-admin-ui]
    end

    subgraph Platform Runtime [OpenCrawling JVM Runtime]
        Core[Core Ingestion Engine - oc-core]
        Runtime[Bootstrap - oc-runtime]
        FS_Conn[Filesystem Repository - oc-filesystem-repository-connector]
        Vec_Conn[Vector Output - oc-vector-output-connector]
        Kafka_Cons[Ingestion Consumer - IngestionConsumer]
        
        Runtime --> Core
        Core --> FS_Conn
        Core -->|Publish IngestionMessage| Kafka[(Kafka Topic: opencrawling-documents)]
        Kafka -->|Consume Reference| Kafka_Cons
        Kafka_Cons -->|Resolve Content & Process| Vec_Conn
    end

    subgraph Infrastructure [Docker Containers]
        PG[(PostgreSQL + pgvector)]
        Redis[(Redis Cache & Session)]
        Ollama[Ollama AI Embeddings]
        Kafka_Broker[Apache Kafka Broker]
    end

    UI_App -->|REST API| Runtime
    Vec_Conn -->|Vectors| PG
    Runtime -->|Job Cache| Redis
    Vec_Conn -->|Generates Embeddings| Ollama
    Kafka --> Kafka_Broker
```

---

## Core Technologies

- **Java 25 Preview Features**: Structured Concurrency, Virtual Threads, and Pattern Matching.
- **Spring Boot & Spring AI**: High-performance backend orchestrating ingestion jobs.
- **Apache Kafka**: Decoupled, event-driven document processing using the **Claim Check Pattern**.
- **pgvector**: High-dimensional vector similarity search in PostgreSQL.
- **Redis Stack**: Lightweight caching and session management.
- **Ollama**: Local AI embedding generation via open-source LLM models.
- **Vite + React + TailwindCSS**: Modern frontend administration dashboard.

---

## Getting Started

### Prerequisites

Ensure you have the following installed on your machine:
- **JDK 25** (Ensure `JAVA_HOME` points to your JDK 25 directory)
- **Maven 3.9+**
- **Docker & Docker Compose**
- **Node.js 18+ & npm** (for the UI)

---

### Step-by-Step Setup

#### 1. Start Infrastructure (Docker)
Spin up the database, cache, message broker, and AI engine. Run from the project root:
```bash
docker compose up -d
```
**Services started:**
* **PostgreSQL (Port 5432)**: For job metadata, schema migrations, and pgvector storage.
* **Redis (Port 6379 / Insight Port 8001)**: For caching and session management.
* **Ollama (Port 11434)**: For local embeddings.
* **Apache Kafka (Port 9092)**: KRaft-mode broker for decoupled, event-driven document processing.

#### 2. Pull the Embedding Model (Ollama)
The platform is configured to use the `mxbai-embed-large` model for embeddings. You must pull it once:
```bash
docker exec -it ollama ollama pull mxbai-embed-large
```
*(You can exit the prompt with `Ctrl+D` once the download starts; Ollama will keep downloading in the background).*

#### 3. Build the Project (Maven)
Compile all modules using Java 25. Since we utilize advanced features, preview features must be enabled:
```bash
mvn clean install
```

#### 4. Run the Runtime Bootstrap
Start the Spring Boot runtime application:
```bash
mvn spring-boot:run -pl oc-runtime -Dspring-boot.run.profiles=dev
```

##### Running a Sample Ingestion Job on Startup (Optional)
By default, the automatic startup crawl is disabled to prevent unnecessary scans. To trigger a demo crawl job on startup, pass the configuration properties:
```bash
mvn spring-boot:run -pl oc-runtime -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--spring.opencrawling.crawl-on-startup=true --spring.opencrawling.scan-path=/your/local/directory/to/scan"
```

#### 5. Run the Admin UI
To launch the administration dashboard:
```bash
cd oc-admin-ui
npm install
npm run dev
```
Open [http://localhost:5173](http://localhost:5173) in your browser.
---

## Scaling Out & Performance

OpenCrawling is designed for high-throughput, horizontal scalability. Since the ingestion pipeline is decoupled using **Apache Kafka** and the **Claim Check Pattern**, you can scale components independently.

### 1. Scaling the Ingestion / Processing (Output Connector)
Vector indexing and embedding generation is typically the primary performance bottleneck because of deep learning model inference (Ollama) and database indexing (pgvector).
* **Kafka Consumer Group Partitioning**: The `opencrawling-documents` topic is consumed by the `IngestionConsumer` inside the `oc-runtime` service. By configuring the topic with multiple partitions, Kafka will distribute documents among active consumers.
* **Horizontal Scaling of Runtime Instances**: You can run multiple instances of the `oc-runtime` application sharing the same `spring.application.name` and consumer group (`opencrawling-vector-group`). Kafka automatically distributes partitions and load-balances the messages.
* **Ollama Load Balancing**: Scale out embedding generation by pointing `spring.ai.ollama.base-url` to a load balancer (e.g., NGINX, HAProxy) backed by a cluster of Ollama instances running on GPU-enabled nodes.

### 2. Scaling the Repository Connectors (Ingestion Source)
The scanning/crawling phase can be distributed by splitting large target sources:
* **Partitioned Scans**: Run separate bootstrap crawl jobs targeting different sub-directories or repository prefixes.
* **Distributed File Shares / Shared Storage**: In a multi-node setup, ensure the `IngestionConsumer` instances have access to the same shared filesystem (e.g., NFS, S3/MinIO bucket, SMB) as the repository crawlers, so the Claim Check reference (path/URI) can be successfully resolved by the consumer node.

### 3. Claim Check Pattern
To ensure the messaging system remains fast and responsive:
1. The **Repository Connector** crawls data, but instead of publishing the entire document content (which could be megabytes of binary data) to Kafka, it saves/references the file on a shared storage medium.
2. It publishes a lightweight `IngestionMessage` (Claim Check record) to the Kafka topic containing the metadata (URI, file path, version).
3. The **Consumer Workers** pull the reference, read the file directly from storage, run splitting/chunking, request embeddings, and save the resulting vectors in pgvector.

---

## Verification & Monitoring

- **Database**: Access PostgreSQL at `localhost:5432` (User: `opencrawling`, DB: `opencrawling`).
- **Redis Dashboard**: Open [http://localhost:8001](http://localhost:8001) in your browser to view the Redis Stack Insight dashboard.
- **Logs**: Monitor console output for the Virtual Thread Executor and Structured Concurrency task logs.

---

## Troubleshooting

- **Java Version Check**: Run `java -version` to confirm you are using Java 25.
- **Preview Features**: If your IDE fails to compile structured concurrency code, verify that the `--enable-preview` JVM argument is configured for compiler and runtime settings. (It is already pre-configured in `pom.xml`).
