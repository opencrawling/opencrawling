# Welcome to the OpenCrawling Wiki 🌐

**OpenCrawling** is a high-performance, enterprise-grade data ingestion, federation, and security mapping framework. It bridges legacy or enterprise content repositories to downstream AI, vector search, and Retrieval-Augmented Generation (RAG) infrastructure.

---

## 🗺️ Wiki Navigation

Use the sidebar or the quick links below to navigate the documentation:

*   **[Home](Home)**: Overview and core concepts.
*   **[Architecture](Architecture)**: Microservices layout, event streams, and the **Claim Check Pattern**.
*   **[Configuration Guide](Configuration-Guide)**: Setting up repository, transformation, and output connectors.
*   **[Model Context Protocol](Model-Context-Protocol)**: Running and query-authorizing the Secure MCP Server.
*   **[Document Authorization](Document-Authorization)**: How retrieval is authorized, and relationship-based access control with OpenFGA.
*   **[Enterprise Multi-Agent Integration](Enterprise-Multi-Agent-Integration)**: How AI agents discover, ingest, govern and read content on behalf of users, with verified identities.

---

## 💡 Core Philosophy

Unlike standard scrapers, OpenCrawling is designed from the ground up for the security and volume demands of enterprise environments:

1.  🔒 **Security First (ACL Mapping)**: Automatically synchronizes document Access Control Lists (ACLs) containing Security SIDs. AI applications and vector queries respect native document rights, ensuring users only see search results they are authorized to access.
2.  ⚡ **Asynchronous Scale (Decoupled Microservices)**: Ingestion tasks run as separate components communicating asynchronously via **Apache Kafka**. Heavy deep-learning embeddings or text extractions can be scaled horizontally without blocking crawlers.
3.  🚀 **Modern JVM Performance**: Built with **Java 25** and **Spring Boot**, utilizing Virtual Threads (Project Loom) and Structured Concurrency for massive, non-blocking network I/O throughput.
