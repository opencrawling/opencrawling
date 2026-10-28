# OpenCrawling Java Client SDK (`oc-java-client-sdk`)

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java Version](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)

The **OpenCrawling Java SDK** (`oc-java-client-sdk`) is a strongly typed, fluent Java client library designed to programmatically manage document ingestion jobs, connectors, system configurations, AIOps diagnostics, and Auto-Narrativization templates against the **OpenCrawling Runtime REST APIs** and Open Ingestion Standard (OIS) models.

---

## 🚀 Key Features

- **Fluent Client API**: Easy initialization with builder pattern, custom endpoints, timeouts, authentication, and HTTP transports.
- **Job Lifecycle Control**: Programmatically `create`, `list`, `get`, `start`, `pause`, `stop`, and `delete` document ingestion jobs.
- **Connector Administration**: Manage repository scanning, vector store output, and AI transformation connectors.
- **Auto-Narrativization Copilot Integration**: Generate Mustache template narratives from schema field definitions.
- **AIOps & Observability**: Retrieve automated root cause analysis (RCA), correlated OpenTelemetry traces, error logs, and throughput metrics.
- **Spring Boot Starter Autoconfiguration**: Embedded Spring Boot support via `opencrawling.client.*` properties.
- **Modern Java 25 & Zero-Dependency Transport**: Built using native `java.net.http.HttpClient` and Jackson.

---

## 📦 Installation

### Maven

Add `oc-java-client-sdk` to your `pom.xml`:

```xml
<dependency>
    <groupId>org.opencrawling</groupId>
    <artifactId>oc-java-client-sdk</artifactId>
    <version>1.0.4-SNAPSHOT</version>
</dependency>
```

---

## 💡 Usage Examples

### 1. Initializing the Client

```java
import org.opencrawling.sdk.OpenCrawlingClient;
import java.time.Duration;

OpenCrawlingClient client = OpenCrawlingClient.builder()
        .baseUrl("http://localhost:8080")
        .apiKey("your-api-key")
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(30))
        .build();
```

### 2. Creating and Starting an Ingestion Job

```java
import org.opencrawling.sdk.models.JobRequest;
import org.opencrawling.sdk.models.JobResponse;
import org.opencrawling.sdk.models.NarrativizationConfig;

// Create job with Auto-Narrativization enabled
JobResponse job = client.jobs().create(
    JobRequest.builder()
        .name("Enterprise Documentation Crawler")
        .targetUrl("https://docs.example.com")
        .repositoryConnector("FileSystem_Local")
        .outputConnector("PGVector_Output")
        .transformationConnector("Ollama_Embedding_Default")
        .narrativization(NarrativizationConfig.builder()
            .enabled(true)
            .template("Document titled {{title}} with content: {{content}}")
            .build())
        .build()
);

System.out.println("Created Job ID: " + job.id());

// Trigger execution
client.jobs().start(job.id());
```

### 3. Registering Connectors

```java
import org.opencrawling.sdk.models.ConnectorRequest;

client.connectors().create(
    ConnectorRequest.builder()
        .name("Custom_PGVector_Output")
        .description("Custom PGVector Output Store")
        .type("output")
        .className("org.opencrawling.vector.VectorOutputConnector")
        .maxConnections(20)
        .addConfiguration("pgVectorUrl", "jdbc:postgresql://localhost:5432/opencrawling")
        .build()
);
```

### 4. Auto-Narrativization Copilot

```java
import org.opencrawling.sdk.models.CopilotRequest;
import org.opencrawling.sdk.models.CopilotResponse;

CopilotResponse copilotResponse = client.narrativization().generateTemplate(
    CopilotRequest.builder()
        .connectorType("repository")
        .addField("title", "string", "Document Title")
        .addField("content", "string", "Extracted text content")
        .build()
);

System.out.println("Generated Template: " + copilotResponse.template());
```

### 5. AIOps Diagnostics & Observability

```java
import org.opencrawling.sdk.models.DiagnosticReport;
import org.opencrawling.sdk.models.JobTraceResponse;

// Run AI-powered Root Cause Analysis
DiagnosticReport report = client.observability().diagnose("1");
System.out.println("Pipeline Health Status: " + report.status());
System.out.println("RCA Summary: " + report.summary());

// Query OpenTelemetry traces
JobTraceResponse traces = client.observability().getTraces("1");
System.out.println("Total Spans: " + traces.totalSpans() + ", Duration: " + traces.totalDurationMillis() + "ms");
```

---

## 🍃 Spring Boot Integration

If using Spring Boot, `OpenCrawlingAutoConfiguration` will automatically create an `OpenCrawlingClient` bean.

In `application.yml`:

```yaml
opencrawling:
  client:
    base-url: http://localhost:8080
    api-key: your-api-key
    connect-timeout: 10s
    read-timeout: 30s
```

Inject the client anywhere in your Spring components:

```java
@Service
public class IngestionService {

    private final OpenCrawlingClient client;

    public IngestionService(OpenCrawlingClient client) {
        this.client = client;
    }

    public void triggerJob(String jobId) {
        client.jobs().start(jobId);
    }
}
```

---

## 🛠️ Publishing & Maven Central Deployment

### Automated GitHub Actions Workflow

Deploying `oc-java-client-sdk` to Maven Central is fully automated using GitHub Actions ([.github/workflows/client-sdk-publish.yml](file:///.github/workflows/client-sdk-publish.yml)).

To trigger a release deployment:
1. Navigate to GitHub **Actions** -> **Publish Java SDK to Maven Central**.
2. Click **Run workflow**.
3. (Optional) Provide a release version (e.g. `1.0.0`).

The workflow will:
- Set up JDK 25 and Maven Central GPG credentials.
- Compile and execute all unit and integration tests.
- Generate signed Javadoc and source JAR artifacts.
- Deploy the published release directly to Sonatype Central Portal.

### Local Maven Installation

For local development and testing across sibling projects (GPG artifact signing is skipped by default):

```bash
mvn clean install -pl oc-java-client-sdk -am
```

### Manual Release Deployment (Enabling GPG Signing)

GPG signing execution is skipped by default (`<gpg.skip>true</gpg.skip>`) so local builds don't require local GPG keys. When deploying a formal release to Maven Central, pass `-Dgpg.skip=false`:

```bash
mvn clean deploy -pl oc-java-client-sdk -DskipTests \
  -DaltDeploymentRepository=central::default::https://central.sonatype.com/api/v1/publisher/deploy \
  -Dgpg.skip=false \
  -Dgpg.passphrase="YOUR_GPG_PASSPHRASE"
```

---

## 📄 License

Licensed under the [Apache License, Version 2.0](LICENSE).
