# Spring Boot CRUD Application with OpenTelemetry Observability

This repository contains a Spring Boot CRUD application instrumented with **OpenTelemetry (OTel)** demonstrating end-to-end distributed observability across **Traces**, **Metrics**, and **Logs**, combining **Zero-Code Automatic Instrumentation** with **Targeted Manual Service-Layer Instrumentation**.

---

## Architecture & Instrumentation Overview

```
                          [ Incoming HTTP Client / curl ]
                                       │
                                       ▼
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │                      OpenTelemetry Java Agent (Zero-Code)                   │
 │  - Intercepts Tomcat HTTP Server: creates SERVER span 'METHOD /path'        │
 │  - Injects W3C Trace Context into Logback MDC (trace_id, span_id)           │
 │  - Intercepts Hibernate & Spring Data JPA: creates INTERNAL & CLIENT spans  │
 └─────────────────────────────────────┬───────────────────────────────────────┘
                                       │
                                       ▼
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │                      Spring Boot Web & Service Layer                        │
 │                                                                             │
 │  UserController (HTTP Routing)                                              │
 │     │ (No manual spans - delegated to automatic HTTP instrumentation)       │
 │     ▼                                                                       │
 │  UserService (Manual OpenTelemetry SDK Instrumentation)                     │
 │     ├── Tracing: Manual child spans ('create-user', 'delete-user')          │
 │     │   - Scoped via span.makeCurrent() to maintain parent-child hierarchy  │
 │     │   - Attributes: operation.name, user.id                               │
 │     │   - Exception handling: span.recordException(e), StatusCode.ERROR     │
 │     │   - Lifecycle safety: span.end() in finally block                     │
 │     │                                                                       │
 │     ├── Metrics: Application operational measurements                       │
 │     │   - users.operation.count (LongCounter: operation, status)            │
 │     │   - users.operation.duration (DoubleHistogram: operation, status)     │
 │     │   - Strict cardinality protection: user.id is NOT a metric attribute  │
 │     │                                                                       │
 │     └── Logs: Structured logging with trace correlation                     │
 │         - Logback pattern: [trace_id=%X{trace_id} span_id=%X{span_id}]      │
 │         - Operation lifecycle logs: started, succeeded, failed              │
 └─────────────────────────────────────────────────────────────────────────────┘
```

### Manual vs Automatic Instrumentation
| Dimension | Automatic Instrumentation (Java Agent) | Manual Instrumentation (OTel API) |
| :--- | :--- | :--- |
| **Scope** | Framework boundaries: HTTP ingress (Tomcat), Spring Data JPA, Hibernate, JDBC. | Business domain logic inside `UserService`. |
| **Mechanism** | Dynamic bytecode manipulation via `-javaagent:opentelemetry-javaagent.jar`. | Explicit OpenTelemetry API calls (`Tracer`, `Meter`, `Span`, `Counter`, `Histogram`). |
| **Tracing** | Root server spans (`HTTP POST /users`, `GET /users/{id}`, `DELETE /users/{id}`). | Nested child spans (`create-user`, `delete-user`) with domain attributes (`user.id`). |
| **Metrics** | JVM runtime metrics (CPU, garbage collection, heap, thread pools, Tomcat threads). | Domain business metrics (`users.operation.count`, `users.operation.duration`). |
| **Logging** | Automatically injects `trace_id` and `span_id` into SLF4J MDC. | Emits structured log events (`operation=create`, `user.id=1`) correlated with active trace. |

---

## Prerequisites

* **Java:** JDK 17 or higher (tested with OpenJDK 21)
* **Maven:** Apache Maven 3.8+
* **OpenTelemetry Java Agent:** `opentelemetry-javaagent.jar` (v2.34.0+ recommended)

---

## Quickstart: Build & Run

### 1. Build Application
Compile and package the executable fat jar:
```bash
mvn clean package
```
The repackaged artifact will be generated at `target/observability-skeleton-0.0.1-SNAPSHOT.jar`.

### 2. Download OpenTelemetry Java Agent (if not present)
```bash
curl -L -o opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.34.0/opentelemetry-javaagent.jar
```

### 3. Run with OpenTelemetry Java Agent (Console / Logging Exporters)
To execute the application and print all traces, metrics, and logs directly to the console/log file:

```bash
java -javaagent:opentelemetry-javaagent.jar \
  -Dotel.service.name=crud-otel-app \
  -Dotel.traces.exporter=logging \
  -Dotel.metrics.exporter=logging \
  -Dotel.logs.exporter=logging \
  -Dotel.metric.export.interval=5000 \
  -jar target/observability-skeleton-0.0.1-SNAPSHOT.jar
```

### Alternative: Run with OpenTelemetry Collector / Jaeger (OTLP)
If an OpenTelemetry Collector or Jaeger is running locally on port 4317 (gRPC) or 4318 (HTTP):
```bash
java -javaagent:opentelemetry-javaagent.jar \
  -Dotel.service.name=crud-otel-app \
  -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
  -Dotel.exporter.otlp.protocol=grpc \
  -jar target/observability-skeleton-0.0.1-SNAPSHOT.jar
```

---

## OpenTelemetry Configuration Properties

| System Property / Env Var | Default | Description |
| :--- | :--- | :--- |
| `-Dotel.service.name` / `OTEL_SERVICE_NAME` | `crud-otel-app` | Logical name of the service emitting telemetry. |
| `-Dotel.traces.exporter` / `OTEL_TRACES_EXPORTER` | `logging` (or `otlp`) | Exporter for distributed traces (`logging`, `otlp`, `none`). |
| `-Dotel.metrics.exporter` / `OTEL_METRICS_EXPORTER` | `logging` (or `otlp`) | Exporter for metric measurements (`logging`, `otlp`, `none`). |
| `-Dotel.logs.exporter` / `OTEL_LOGS_EXPORTER` | `logging` (or `otlp`) | Exporter for log records (`logging`, `otlp`, `none`). |
| `-Dotel.metric.export.interval` / `OTEL_METRIC_EXPORT_INTERVAL` | `5000` | Periodic metric export flush frequency in milliseconds. |

---

## Generating API Traffic

The application runs on `http://localhost:8080`.

### 1. Success Scenarios
* **Create User (POST /users):**
  ```bash
  curl -i -X POST http://localhost:8080/users \
    -H "Content-Type: application/json" \
    -d '{"name":"Alice Wonder","email":"alice.wonder@example.com"}'
  # Expected Response: HTTP 201 Created with JSON body {"id":4,"name":"Alice Wonder",...}
  ```

* **Retrieve User (GET /users/1):**
  ```bash
  curl -i http://localhost:8080/users/1
  # Expected Response: HTTP 200 OK with JSON body {"id":1,"name":"John Doe",...}
  ```

* **Delete User (DELETE /users/1):**
  ```bash
  curl -i -X DELETE http://localhost:8080/users/1
  # Expected Response: HTTP 204 No Content
  ```

### 2. Failure Scenarios
* **Get Non-existent User (GET /users/999):**
  ```bash
  curl -i http://localhost:8080/users/999
  # Expected Response: HTTP 404 Not Found with error JSON
  ```

* **Delete Non-existent User (DELETE /users/999):**
  ```bash
  curl -i -X DELETE http://localhost:8080/users/999
  # Expected Response: HTTP 404 Not Found with error JSON
  ```

* **Duplicate Email Conflict (POST /users):**
  ```bash
  curl -i -X POST http://localhost:8080/users \
    -H "Content-Type: application/json" \
    -d '{"name":"Duplicate User","email":"john@example.com"}'
  # Expected Response: HTTP 409 Conflict with message "Email already exists: john@example.com"
  ```

---

## Telemetry Evidence & Verification

### 1. Traces
* **Automatic HTTP Spans:** Handled entirely by the Java Agent. For `GET /users/{id}`, only the automatic HTTP span is produced.
* **Parent-Child DELETE Trace:**
  ```text
  [automatic] SERVER 'DELETE /users/{id}' (HTTP 204)
    └── [manual] INTERNAL 'delete-user' (user.id=1, operation.name=delete-user)
          ├── [auto] INTERNAL 'UserRepository.findById'
          ├── [auto] INTERNAL 'UserRepository.delete'
          └── [auto] CLIENT   'DELETE observabilitydb.users' (SQL)
  ```
* **Error Tracing:** When an operation fails (e.g., `DELETE /users/999`), the child span is marked with `StatusCode.ERROR`, the `UserNotFoundException` stack trace is recorded on the span, and the parent HTTP span logs status code `404`.

### 2. Metrics
Emitted by `LoggingMetricExporter` every 5 seconds:
* **`users.operation.count` (Counter):**
  * Attributes: `operation` (`create | get | delete`), `status` (`success | error`).
  * Exemplars link each metric increment directly to the active `trace_id`.
  * **Cardinality Guarantee:** High-cardinality attributes like `user.id` or email are **strictly prohibited** from metric attributes.
* **`users.operation.duration` (Histogram):**
  * Records operation duration in milliseconds (`ms`) with standard latency buckets.

### 3. Correlated Logs
Application logs are formatted via Logback using:
```properties
logging.pattern.console=%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{36} [trace_id=%X{trace_id} span_id=%X{span_id}] - %msg%n
```
Every log record carries the active `trace_id` and `span_id`:
```text
2026-11-24T15:05:31.416+05:30 INFO  [http-nio-8080-exec-6] c.e.o.service.UserService [trace_id=6824e98819253932775cfc97709f99c5 span_id=e04710b56fd0b26a] - Operation started | operation=delete, user.id=1
2026-11-24T15:05:31.422+05:30 INFO  [http-nio-8080-exec-6] c.e.o.service.UserService [trace_id=6824e98819253932775cfc97709f99c5 span_id=e04710b56fd0b26a] - Operation succeeded | operation=delete, user.id=1
```

---

## Detailed Assignment Solution

For the comprehensive assignment deliverables, including the **Troubleshooting Exercise answers**, the **Best Practices Review**, and the **Knowledge Check (10 MCQs)**, please refer to:
* [docs/SOLUTION.md](docs/SOLUTION.md)
