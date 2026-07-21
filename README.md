# OpenCrawling

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat)](LICENSE)
[![GitHub Stars](https://img.shields.io/github/stars/opencrawling/opencrawling?style=flat&logo=github)](https://github.com/opencrawling/opencrawling/stargazers)
[![GitHub Issues](https://img.shields.io/github/issues/opencrawling/opencrawling.svg?style=flat&logo=github)](https://github.com/opencrawling/opencrawling/issues)
[![GitHub Commit Activity](https://img.shields.io/github/commit-activity/m/opencrawling/opencrawling.svg?style=flat&logo=github)](https://github.com/opencrawling/opencrawling/commits/main)
[![Java Version](https://img.shields.io/badge/Java-25-orange.svg?style=flat&logo=openjdk&logoColor=white)](https://jdk.java.net/25/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1+-green.svg?style=flat&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring_AI-2.0+-6DB33F.svg?style=flat&logo=spring&logoColor=white)](https://spring.io/projects/spring-ai)
[![Docker](https://img.shields.io/badge/Docker-Supported-blue.svg?style=flat&logo=docker&logoColor=white)](https://www.docker.com/)
[![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-Supported-black.svg?style=flat&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![Apache Iceberg](https://img.shields.io/badge/Apache_Iceberg-1.11.0-3a6bc8.svg?style=flat&logo=apache&logoColor=white)](https://iceberg.apache.org/)
[![Apache Ozone](https://img.shields.io/badge/Apache_Ozone-2.2.0-FF6600.svg?style=flat&logo=apache&logoColor=white)](https://ozone.apache.org/)
[![Apache Tika](https://img.shields.io/badge/Apache_Tika-3.x-007396.svg?style=flat&logo=apache&logoColor=white)](https://tika.apache.org/)
[![Alfresco](https://img.shields.io/badge/Alfresco_Content_Services-Supported-0090DF.svg?style=flat&logo=alfresco&logoColor=white)](https://www.alfresco.com/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17+-blue.svg?style=flat&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Milvus](https://img.shields.io/badge/Milvus-2.4.5-00A1EA.svg?style=flat&logo=milvus&logoColor=white)](https://milvus.io/)
[![Redis](https://img.shields.io/badge/Redis-Supported-red.svg?style=flat&logo=redis&logoColor=white)](https://redis.io/)
[![Ollama](https://img.shields.io/badge/Ollama-0.23.4-white.svg?style=flat&logo=ollama&logoColor=black)](https://ollama.com/)
[![OIS](https://img.shields.io/badge/OIS-Open_Ingestion_Standard-0052CC.svg?style=flat)](https://github.com/opencrawling/open-ingestion-standard)
[![MCP](https://img.shields.io/badge/MCP-Model_Context_Protocol-8A2BE2.svg?style=flat&logo=anthropic&logoColor=white)](https://modelcontextprotocol.io/)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/opencrawling/opencrawling)

**OpenCrawling** is the reference Java and Spring Framework implementation of the **[Open Ingestion Standard (OIS)](https://github.com/opencrawling/open-ingestion-standard)**. It provides a secure, decoupled, and vendor-neutral enterprise data integration platform leveraging modern Java 25 features (such as Structured Concurrency and Virtual Threads), Spring Boot, Spring AI, and vector search infrastructure to orchestrate data flows from various repository connectors to vector search outputs.

<p align="center">
  <img src="https://github.com/opencrawling/.github/raw/main/profile/images/logo.png" alt="OpenCrawling Logo" width="200" />
</p>

---

## Architecture Diagram

The diagram below shows the high-level architecture of OpenCrawling, highlighting the newly decoupled, stateless embedding microservice and transformation connectors:

```mermaid
graph TD
    subgraph UI
        UI_App[Admin React UI - oc-admin-ui]
    end

    subgraph PlatformRuntime [OpenCrawling Ingestion Runtime - oc-runtime]
        Runtime_Node([OpenCrawling Ingestion Runtime])
        Core[Core Ingestion Engine - oc-core]
        FS_Conn[Filesystem Repository - oc-filesystem-repository-connector]
        
        Ing_Cons[Ingestion Consumer - IngestionConsumer]
        Tika[Apache Tika Text Extractor]
        Chunker[Token Chunker]
        
        Writer_Cons[Vector Store Writer - VectorStoreWriterConsumer]
        Precompute_Model[PrecomputedEmbeddingModel]
        Vec_Conn[Vector Store Output - oc-vector-output-connector]

        McpServer[Secure MCP Server - McpVectorServer]
        
        Core --> FS_Conn
        Core -->|Publish IngestionMessage| Ingest_Topic[(Kafka Topic: opencrawling-ingestion)]
        
        Ingest_Topic -->|Consume IngestionMessage| Ing_Cons
        Ing_Cons -->|Extract Text| Tika
        Tika --> Chunker
        Chunker -->|Publish Chunks| Chunk_Topic[(Kafka Topic: opencrawling-chunks)]
        
        Embed_Topic[(Kafka Topic: opencrawling-embedded)] -->|Consume EmbeddedMessage| Writer_Cons
        Writer_Cons --> Precompute_Model
        Precompute_Model --> Vec_Conn

        McpServer -->|Queries - Enforces ACLs| Vec_Conn
    end

    subgraph Embedding Service [OpenCrawling Embedding Service - oc-embedding-service]
        Embed_Cons[Embedding Consumer - EmbeddingConsumer]
        Model_Factory[EmbeddingModelFactory]
        
        Chunk_Topic -->|Consume ChunkMessage| Embed_Cons
        Embed_Cons --> Model_Factory
        Embed_Cons -->|Publish Embedded| Embed_Topic
    end

    subgraph Infrastructure [Docker Containers]
        PG[(PostgreSQL + pgvector)]
        Redis[(Redis Cache & Session)]
        Ollama[Ollama AI Embeddings]
        Kafka_Broker[Apache Kafka Broker]
    end

    subgraph External [AI Clients]
        LLM[AI Client / LLM Agent]
        OpenAI[OpenAI Platform]
    end

    UI_App -->|REST API| Runtime_Node
    Vec_Conn -->|Vectors| PG
    Runtime_Node -->|Job Cache| Redis
    
    Model_Factory -->|Local Inference| Ollama
    Model_Factory -->|Cloud Inference| OpenAI
    
    Ingest_Topic --> Kafka_Broker
    Chunk_Topic --> Kafka_Broker
    Embed_Topic --> Kafka_Broker

    LLM -->|Model Context Protocol| McpServer

```

---

## Administration Dashboard (oc-admin-ui)

The `oc-admin-ui` provides a modern web-based administration console to monitor and configure your ingestion jobs.

### UI Screenshots

#### 📊 Telemetry Dashboard
![Dashboard Telemetry](images/screenshots/ui-dashboard.png)
*Real-time graphs monitoring job success rates, Kafka queue load, active crawling threads, and index ingestion speed.*

#### 📋 Job Pipeline Scheduler
![Pipeline Job Management](images/screenshots/ui-pipeline-job-management.png)
*Schedule, monitor, start, and pause ingestion crawl tasks. Review document indexing status reports.*

#### 📁 Connector Configurations
![Connector Registry Configuration](images/screenshots/ui-connector-configuration.png)
*Manage endpoints and credentials for repositories (SharePoint, S3, Filesystem), output vectors, and transformation engines.*

#### ⚙️ Ingestion & Embedding Mappings
![Ingestion & Embedding Settings](images/screenshots/ui-ingestion-and-embedding-settings.png)
*Configure target models (e.g., Ollama, OpenAI) and tune text chunk sizes/overlap boundaries dynamically.*

#### 🪵 Real-Time Ingestion Logs
![Real-Time Activity Logs](images/screenshots/ui-real-time-activity-logs.png)
*Inspect live Java logging streams and Kafka consumer offsets to troubleshoot connector execution.*

---

## Core Technologies

- **Java 25 Preview Features**: Structured Concurrency, Virtual Threads, and Pattern Matching.
- **Spring Boot & Spring AI**: High-performance backend orchestrating ingestion jobs.
- **Apache Kafka**: Decoupled, event-driven document processing using the **Claim Check Pattern**.
- **pgvector**: High-dimensional vector similarity search in PostgreSQL.
- **Milvus**: High-performance, distributed vector database for large-scale enterprise vector indexing.
- **Redis Stack**: Lightweight caching and session management.
- **Ollama & OpenAI**: Dynamic embedding generation via local and cloud-based AI engines.
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

#### 2. Pull the Embedding Models (Ollama)

OpenCrawling supports configuring different embedding models on a per-job basis and automatically routes them to corresponding PgVector tables. To use the available options, make sure to pull the models you plan to utilize:

*   **mxbai-embed-large (1024-dim, default)**:
    ```bash
    docker exec -it ollama ollama pull mxbai-embed-large
    ```
*   **nomic-embed-text (768-dim)**:
    ```bash
    docker exec -it ollama ollama pull nomic-embed-text
    ```
*   **all-minilm (384-dim)**:
    ```bash
    docker exec -it ollama ollama pull all-minilm
    ```
*(Ollama will download the requested models in the background. Once pulled, OpenCrawling will automatically route them to `vector_store_1024`, `vector_store_768`, or `vector_store_384` respectively).*

---

### Option A: Run OpenCrawling in Docker Containers (Recommended)

To build and run the OpenCrawling backend runtime, the dynamic embedding microservice, and the administration UI as containerized services, run:

1. **Build the images**:
   ```bash
   docker compose -f docker-compose-apps.yml build
   ```

2. **Start the applications**:
   ```bash
   docker compose -f docker-compose-apps.yml up -d
   ```

* **Backend Service**: Access the backend runtime and integrated static resources at [http://localhost:8080](http://localhost:8080).
* **Embedding Service**: The dynamic microservice processes embeddings at [http://localhost:8082](http://localhost:8082).
* **Frontend Service**: Access the standalone React Administration Console at [http://localhost:3000](http://localhost:3000).

---

### Option A.2: Decoupled Multi-Service Deployment (Decoupled Microservices)

To run each microservice component (Repository Crawler, Ingestion Consumer, Embedding Consumer, Vector Store Writer, Secure MCP Server, and Admin UI) as a completely separate containerized process communicating over Kafka:

1. **Build the decoupled service images**:
   ```bash
   docker compose -f docker-compose-decoupled.yml build
   ```

2. **Start the complete decoupled pipeline**:
   ```bash
   docker compose -f docker-compose-decoupled.yml up -d
   ```

This spins up the database/event-stream dependencies alongside five decoupled OpenCrawling service containers. You can view logs, scale individual workers (e.g. `docker compose -f docker-compose-decoupled.yml scale oc-embedding-service=3`), and monitor the decoupled pipeline.

* **React Admin UI Console**: Access the administration dashboard at [http://localhost:3000](http://localhost:3000).
* **Secure MCP Server**: Connect your AI Client / IDE directly to [http://localhost:8080](http://localhost:8080) over SSE.

### Option A.3: Quick Start with Released Containers (Pre-built Release Distribution)

To run the complete decoupled pipeline using the official pre-built release containers from the GitHub Container Registry (without building the services locally):

1. **Start the release pipeline**:
   ```bash
   docker compose -f docker-compose-decoupled-dist.yml up -d
   ```

This pulls the official `ghcr.io/opencrawling/...` images directly, allowing you to spin up the entire architecture (Crawler, Ingestion, Embedding Service, Writer, MCP Server, and Admin UI) instantly.

### Option A.4: Decoupled Milvus-Based Deployment (Standalone + etcd + MinIO)

To run the complete decoupled pipeline configured to use Milvus instead of PostgreSQL/pgvector:

1. **Build the Milvus decoupled stack**:
   ```bash
   docker compose -f oc-milvus-output-connector/docker/docker-compose-decoupled-with-milvus.yml build
   ```

2. **Start the Milvus decoupled pipeline**:
   ```bash
   docker compose -f oc-milvus-output-connector/docker/docker-compose-decoupled-with-milvus.yml up -d
   ```

This starts the etcd, MinIO, and Milvus Standalone infrastructure alongside the decoupled OpenCrawling services. 

---

#### Running the Decoupled Integration Tests

We provide fully automated end-to-end integration test scripts that build, boot, test, and cleanse the entire decoupled environment:

*   **PGVector Decoupled Pipeline**:
    ```bash
    ./scripts/test-docker-decoupled.sh
    ```
    This script tests the decoupled architecture using PostgreSQL and pgvector, verifying database content directly.

*   **Milvus Decoupled Pipeline**:
    ```bash
    ./scripts/test-milvus-decoupled.sh
    ```
    This script tests the decoupled architecture using Milvus, querying the Milvus REST API to verify row ingestion and checking Secure MCP Server endpoints.

*   **Apache Ozone Object Storage Claim Check Pipeline**:
    ```bash
    ./scripts/test-ozone-decoupled.sh
    ```
    This script tests the decoupled architecture using Apache Ozone 2.2.0 (SCM, OM, Datanode, S3 Gateway) as the Claim Check Object Store for large document payloads.

---

### Option B: Run OpenCrawling Locally (Development Mode)

If you wish to run the JVM runtime and React frontend directly on your host machine for development:

#### 1. Build the Project (Maven)
Compile all modules using Java 25. Since we utilize advanced features, preview features must be enabled:
```bash
mvn clean install
```

#### 2. Run the Ingestion Runtime Bootstrap
Start the Spring Boot runtime application:
```bash
mvn spring-boot:run -pl oc-runtime -Dspring-boot.run.profiles=dev
```

#### 3. Run the Embedding Service Microservice
Start the Embedding Service application in a separate terminal:
```bash
mvn spring-boot:run -pl oc-embedding-service
```

##### Running a Sample Ingestion Job on Startup (Optional)
By default, the automatic startup crawl is disabled to prevent unnecessary scans. To trigger a demo crawl job on startup, pass the configuration properties:
```bash
mvn spring-boot:run -pl oc-runtime -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--spring.opencrawling.crawl-on-startup=true --spring.opencrawling.scan-path=/your/local/directory/to/scan"
```

#### 4. Run the Admin UI
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
Vector indexing and embedding generation is typically the primary performance bottleneck because of deep learning model inference (Ollama/OpenAI) and database indexing (pgvector).
* **Kafka Consumer Group Partitioning**: The three main topics (`opencrawling-ingestion`, `opencrawling-chunks`, and `opencrawling-embedded`) are consumed by `IngestionConsumer`, `EmbeddingConsumer` (in `oc-embedding-service`), and `VectorStoreWriterConsumer` respectively within the OpenCrawling services. By configuring these topics with multiple partitions, Kafka distributes load dynamically among active consumer nodes.
* **Horizontal Scaling of Service Instances**: You can run multiple instances of the `oc-embedding-service` application sharing the same consumer group. Kafka automatically distributes partitions and load-balances the messages.
* **Ollama Load Balancing**: Scale out embedding generation by pointing `baseUrl` to a load balancer (e.g., NGINX, HAProxy) backed by a cluster of Ollama instances running on GPU-enabled nodes.

### 2. Scaling the Repository Connectors (Ingestion Source)
The scanning/crawling phase can be distributed by splitting large target sources:
* **Partitioned Scans**: Run separate bootstrap crawl jobs targeting different sub-directories or repository prefixes.
* **Distributed File Shares / Shared Storage**: In a multi-node setup, ensure the `IngestionConsumer` instances have access to the same shared filesystem (e.g., NFS, S3/MinIO bucket, SMB) as the repository crawlers, so the Claim Check reference (path/URI) can be successfully resolved by the consumer node.

### 3. Claim Check Pattern
To ensure the messaging system remains fast and responsive:
1. The **Repository Connector** crawls data, but instead of publishing the entire document content (which could be megabytes of binary data) to Kafka, it saves/references the file on a shared storage medium.
2. It publishes a lightweight `IngestionMessage` (Claim Check record) to the Kafka topic containing the metadata (URI, file path, version).
3. The **Consumer Workers** process the ingestion:
   * **`IngestionConsumer`** pulls the reference, reads the file directly from storage, extracts text with **Apache Tika**, splits it into semantic chunks, and publishes them to the chunks topic.
   * **`EmbeddingConsumer`** (running in the `oc-embedding-service` microservice) pulls the chunks, reads the dynamically configured Transformation Connector engine configurations, requests embedding vectors from the target model engine (Ollama, OpenAI, Hugging Face, etc.), and publishes the embedded chunks to the embedded topic.
   * **`VectorStoreWriterConsumer`** consumes embedded chunks and uses a stateless `PrecomputedEmbeddingModel` to save them directly to pgvector.

---

## Verification & Monitoring

- **Database**: Access PostgreSQL at `localhost:5432` (User: `opencrawling`, DB: `opencrawling`).
- **Redis Dashboard**: Open [http://localhost:8001](http://localhost:8001) in your browser to view the Redis Stack Insight dashboard.
- **Logs**: Monitor console output for the Virtual Thread Executor and Structured Concurrency task logs.

---

## Troubleshooting

- **Java Version Check**: Run `java -version` to confirm you are using Java 25.
- **Preview Features**: If your IDE fails to compile structured concurrency code, verify that the `--enable-preview` JVM argument is configured for compiler and runtime settings. (It is already pre-configured in `pom.xml`).

---

## 📬 Contact & Support

Join our [Slack Community](https://join.slack.com/t/opencrawling/shared_invite/zt-43r2anb6q-YLoBsOrxCCcBWU5Up3P1rw) to chat with developers, share feedback, or ask configuration questions.

For general inquiries, community updates, or security-sensitive disclosures, please contact the maintainers at [info@opencrawling.org](mailto:info@opencrawling.org).

---

## Trademark

OpenCrawling&reg; is a registered trademark of the OpenCrawling Organization. For guidelines on using the name and logo, please refer to the [TRADEMARK.md](TRADEMARK.md) file.
