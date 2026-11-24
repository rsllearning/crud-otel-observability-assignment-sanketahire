# OpenTelemetry Practical Assignment — Comprehensive Solution

**Course:** Observability Technique — OpenTelemetry  
**Repository:** [crud-otel-observability-assignment-sanketahire](https://github.com/rsllearning/crud-otel-observability-assignment-sanketahire)  
**Author:** Sanket Ahire  

---

# Table of Contents
1. [Executive Summary & Architecture](#1-executive-summary--architecture)
2. [Practical Implementation Task (Code Modifications)](#2-practical-implementation-task-code-modifications)
3. [Telemetry Evidence Portfolio](#3-telemetry-evidence-portfolio)
4. [Troubleshooting Exercise (Failure Analysis)](#4-troubleshooting-exercise-failure-analysis)
5. [Best Practices Review](#5-best-practices-review)
6. [Knowledge Check (10 MCQs with Technical Justifications)](#6-knowledge-check-10-mcqs-with-technical-justifications)

---

# 1. Executive Summary & Architecture

The objective of this assignment is to demonstrate the practical application of OpenTelemetry in a Spring Boot CRUD service by establishing full distributed observability across **Traces**, **Metrics**, and **Logs**.

### Core Architecture Design
* **Zero-Code Automatic Instrumentation:** Provided via `opentelemetry-javaagent.jar`. Automatically instruments HTTP/Tomcat ingress, Spring Data JPA repositories, Hibernate sessions, JDBC drivers, and SLF4J MDC trace context injection.
* **Targeted Manual Instrumentation:** Implemented via OpenTelemetry API `1.55.0` in `UserService.java`.
  * **Traces:** Manual child spans for `create-user` and `delete-user` with domain attributes (`operation.name`, `user.id`), exception recording, error status marking, and lifecycle management in `finally` blocks. No manual spans created for `GET` or in controllers.
  * **Metrics:** Custom `users.operation.count` (LongCounter) and `users.operation.duration` (DoubleHistogram) recording operations (`create`, `get`, `delete`) and statuses (`success`, `error`). `user.id` is strictly omitted to preserve low metric cardinality.
  * **Logs:** Structured logging with correlated `trace_id` and `span_id` injected into Logback console output.

```
HTTP Request ──► [Tomcat Server Span: HTTP DELETE /users/{id}] (Automatic)
                       │
                       ▼
                 [UserService Span: 'delete-user'] (Manual)
                   ├── Attribute: user.id=1
                   ├── Attribute: operation.name=delete-user
                   │
                   ├──► [Spring Data Span: UserRepository.findById] (Automatic)
                   ├──► [Spring Data Span: UserRepository.delete]   (Automatic)
                   └──► [JDBC Span: DELETE observabilitydb.users]   (Automatic)
```

---

# 2. Practical Implementation Task (Code Modifications)

### A. `pom.xml` Dependency Configuration
To avoid version conflicts with Spring Boot 3.1.4's transitively managed OpenTelemetry dependencies, the `opentelemetry-bom` was declared with precedence in `<dependencyManagement>`, and `spring-boot-maven-plugin` repackaging was configured:

```xml
<dependencyManagement>
    <dependencies>
        <!-- OpenTelemetry BOM declared first for version precedence -->
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-bom</artifactId>
            <version>1.55.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>${spring-boot.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- OpenTelemetry API dependency only (SDK provided by Java Agent) -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-api</artifactId>
    </dependency>
</dependencies>
```

---

### B. `OpenTelemetryConfig.java`
Spring configuration class exposing beans initialized from `GlobalOpenTelemetry`:

```java
package com.example.observability.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfig {

    private static final String INSTRUMENTATION_NAME = "com.example.observability";
    private static final String INSTRUMENTATION_VERSION = "0.0.1";

    @Bean
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);
    }

    @Bean
    public Meter meter(OpenTelemetry openTelemetry) {
        return openTelemetry.getMeter(INSTRUMENTATION_NAME);
    }
}
```

---

### C. `UserService.java` (Complete Instrumented Implementation)
Implements manual spans, metric instruments, and structured logging:

```java
package com.example.observability.service;

import com.example.observability.dto.UserRequest;
import com.example.observability.dto.UserResponse;
import com.example.observability.entity.User;
import com.example.observability.exception.DuplicateEmailException;
import com.example.observability.exception.UserNotFoundException;
import com.example.observability.repository.UserRepository;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final AttributeKey<String> ATTR_OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> ATTR_STATUS = AttributeKey.stringKey("status");

    private static final String OP_CREATE = "create";
    private static final String OP_GET = "get";
    private static final String OP_DELETE = "delete";

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_ERROR = "error";

    private final UserRepository userRepository;
    private final Tracer tracer;
    private final LongCounter operationCount;
    private final DoubleHistogram operationDuration;

    public UserService(UserRepository userRepository, Tracer tracer, Meter meter) {
        this.userRepository = userRepository;
        this.tracer = tracer;
        this.operationCount = meter.counterBuilder("users.operation.count")
                .setDescription("Total number of user operations")
                .setUnit("{operations}")
                .build();
        this.operationDuration = meter.histogramBuilder("users.operation.duration")
                .setDescription("Duration of user operations in milliseconds")
                .setUnit("ms")
                .build();
    }

    @Transactional
    public UserResponse createUser(UserRequest request) {
        long startTime = System.currentTimeMillis();
        String status = STATUS_SUCCESS;
        log.info("Operation started | operation={}", OP_CREATE);

        Span span = tracer.spanBuilder("create-user")
                .setAttribute("operation.name", "create-user")
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new DuplicateEmailException("Email already exists: " + request.getEmail());
            }

            User user = new User(request.getName(), request.getEmail());
            User saved = userRepository.save(user);

            span.setAttribute("user.id", saved.getId());
            log.info("Operation succeeded | operation={}, user.id={}", OP_CREATE, saved.getId());
            return toResponse(saved);
        } catch (Exception e) {
            status = STATUS_ERROR;
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            log.error("Operation failed | operation={}, error={}", OP_CREATE, e.getMessage());
            throw e;
        } finally {
            span.end();
            recordMetrics(OP_CREATE, status, startTime);
        }
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        long startTime = System.currentTimeMillis();
        String status = STATUS_SUCCESS;
        log.info("Operation started | operation={}, user.id={}", OP_GET, id);

        try {
            User user = userRepository.findById(id)
                    .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));

            log.info("Operation succeeded | operation={}, user.id={}", OP_GET, id);
            return toResponse(user);
        } catch (Exception e) {
            status = STATUS_ERROR;
            log.error("Operation failed | operation={}, user.id={}, error={}", OP_GET, id, e.getMessage());
            throw e;
        } finally {
            recordMetrics(OP_GET, status, startTime);
        }
    }

    @Transactional
    public void deleteUser(Long id) {
        long startTime = System.currentTimeMillis();
        String status = STATUS_SUCCESS;
        log.info("Operation started | operation={}, user.id={}", OP_DELETE, id);

        Span span = tracer.spanBuilder("delete-user")
                .setAttribute("operation.name", "delete-user")
                .setAttribute("user.id", id)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            User user = userRepository.findById(id)
                    .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));

            userRepository.delete(user);
            log.info("Operation succeeded | operation={}, user.id={}", OP_DELETE, id);
        } catch (Exception e) {
            status = STATUS_ERROR;
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            log.error("Operation failed | operation={}, user.id={}, error={}", OP_DELETE, id, e.getMessage());
            throw e;
        } finally {
            span.end();
            recordMetrics(OP_DELETE, status, startTime);
        }
    }

    private void recordMetrics(String operation, String status, long startTimeMs) {
        long duration = System.currentTimeMillis() - startTimeMs;
        Attributes attributes = Attributes.of(
                ATTR_OPERATION, operation,
                ATTR_STATUS, status
        );
        operationCount.add(1, attributes);
        operationDuration.record((double) duration, attributes);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}
```

---

### D. `application.properties`
Configures Logback console logging pattern with MDC correlation:
```properties
spring.datasource.url=jdbc:h2:mem:observabilitydb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=true

logging.pattern.console=%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{36} [trace_id=%X{trace_id} span_id=%X{span_id}] - %msg%n
```

---

# 3. Telemetry Evidence Portfolio

All telemetry evidence below was generated during execution with the OpenTelemetry Java Agent (`v2.34.0`) and Logging Exporters (`Option C`).

## Evidence 1: Distributed Tracing

### A. Successful Request: `DELETE /users/1`
* **Trace ID:** `6824e98819253932775cfc97709f99c5`
* **Hierarchy:**
  ```text
  [automatic] SERVER 'DELETE /users/{id}' (Span ID: 0185bb4da8df1ecd, HTTP 204)
    └── [manual] INTERNAL 'delete-user'   (Span ID: e04710b56fd0b26a, user.id=1)
          ├── [auto] INTERNAL 'UserRepository.findById'
          ├── [auto] INTERNAL 'UserRepository.delete'
          └── [auto] CLIENT   'DELETE observabilitydb.users' (SQL)
  ```
* **Raw Telemetry Log Extract:**
  ```text
  [otel.javaagent] INFO LoggingSpanExporter - 'delete-user' : 6824e98819253932775cfc97709f99c5 e04710b56fd0b26a INTERNAL [tracer: com.example.observability:0.0.1] AttributesMap{data={thread.id=48, thread.name=http-nio-8080-exec-6, user.id=1, operation.name=delete-user}, capacity=128, totalAddedValues=4}

  [otel.javaagent] INFO LoggingSpanExporter - 'DELETE /users/{id}' : 6824e98819253932775cfc97709f99c5 0185bb4da8df1ecd SERVER [tracer: io.opentelemetry.tomcat-10.0:2.34.0-alpha] AttributesMap{data={http.request.method=DELETE, http.route=/users/{id}, url.path=/users/1, http.response.status_code=204, server.port=8080}, capacity=128, totalAddedValues=14}
  ```

---

### B. Failed Request: `DELETE /users/999`
* **Trace ID:** `c7a903843cca4e90f5d87ceec35a09e7`
* **Hierarchy & Error Metadata:**
  ```text
  [automatic] SERVER 'DELETE /users/{id}' (Span ID: 65cee471b63ded55, HTTP 404)
    └── [manual] INTERNAL 'delete-user'   (Span ID: 0b1e64ce384c6112, user.id=999, Status: ERROR)
          └── Event: 'exception' (com.example.observability.exception.UserNotFoundException)
  ```
* **Raw Telemetry Log Extract:**
  ```text
  [otel.javaagent] INFO LoggingSpanExporter - 'delete-user' : c7a903843cca4e90f5d87ceec35a09e7 0b1e64ce384c6112 INTERNAL [tracer: com.example.observability:0.0.1] AttributesMap{data={thread.id=49, thread.name=http-nio-8080-exec-7, user.id=999, operation.name=delete-user}, capacity=128, totalAddedValues=4}

  [otel.javaagent] INFO LoggingSpanExporter - 'DELETE /users/{id}' : c7a903843cca4e90f5d87ceec35a09e7 65cee471b63ded55 SERVER [tracer: io.opentelemetry.tomcat-10.0:2.34.0-alpha] AttributesMap{data={http.request.method=DELETE, http.route=/users/{id}, url.path=/users/999, http.response.status_code=404, server.port=8080}, capacity=128, totalAddedValues=14}
  ```

---

## Evidence 2: Application Metrics

### A. Counter: `users.operation.count`
* **Type:** `LONG_SUM` (Cumulative)
* **Cardinality Guarantee:** Attributes include only `operation` and `status`. **`user.id` is strictly excluded.**

| Operation | Status | Value | Exemplar Trace ID | Exemplar Span ID |
| :--- | :--- | :---: | :--- | :--- |
| `create` | `success` | 1 | `14a6030c8af4707ea973df131846f1ea` | `853898b47a76f2cc` |
| `create` | `error` | 1 | `4497aa1aa76dbb45668dcb4eff33ceeb` | `0a7a43ca43dde4cd` |
| `get` | `success` | 2 | `0ef5d41fcf7a1e18dae48a41cf1e4f06` | `c24618f7ed417e96` |
| `get` | `error` | 1 | `3ebb8d57902c44f0ffe933f2db9f9b56` | `c92ee0bd3974376c` |
| `delete` | `success` | 1 | `6824e98819253932775cfc97709f99c5` | `0185bb4da8df1ecd` |
| `delete` | `error` | 1 | `c7a903843cca4e90f5d87ceec35a09e7` | `65cee471b63ded55` |

---

### B. Histogram: `users.operation.duration`
* **Type:** `HISTOGRAM` (Cumulative)
* **Unit:** `ms`

| Operation | Status | Count | Min (ms) | Max (ms) | Sum (ms) |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `create` | `success` | 1 | 33.0 | 33.0 | 33.0 |
| `create` | `error` | 1 | 5.0 | 5.0 | 5.0 |
| `get` | `success` | 2 | 1.0 | 15.0 | 16.0 |
| `get` | `error` | 1 | 2.0 | 2.0 | 2.0 |
| `delete` | `success` | 1 | 6.0 | 6.0 | 6.0 |
| `delete` | `error` | 1 | 2.0 | 2.0 | 2.0 |

---

## Evidence 3: Logs with Trace Context Correlation

Correlated logs generated during CRUD test execution:

```text
2026-11-24T15:05:31.321+05:30 INFO  [http-nio-8080-exec-2] c.e.o.service.UserService [trace_id=14a6030c8af4707ea973df131846f1ea span_id=853898b47a76f2cc] - Operation started | operation=create
2026-11-24T15:05:31.343+05:30 INFO  [http-nio-8080-exec-2] c.e.o.service.UserService [trace_id=14a6030c8af4707ea973df131846f1ea span_id=853898b47a76f2cc] - Operation succeeded | operation=create, user.id=4

2026-11-24T15:05:31.359+05:30 INFO  [http-nio-8080-exec-3] c.e.o.service.UserService [trace_id=4497aa1aa76dbb45668dcb4eff33ceeb span_id=30071b76d293d5dc] - Operation started | operation=create
2026-11-24T15:05:31.364+05:30 ERROR [http-nio-8080-exec-3] c.e.o.service.UserService [trace_id=4497aa1aa76dbb45668dcb4eff33ceeb span_id=0a7a43ca43dde4cd] - Operation failed | operation=create, error=Email already exists: alice.wonder@example.com

2026-11-24T15:05:31.416+05:30 INFO  [http-nio-8080-exec-6] c.e.o.service.UserService [trace_id=6824e98819253932775cfc97709f99c5 span_id=e04710b56fd0b26a] - Operation started | operation=delete, user.id=1
2026-11-24T15:05:31.422+05:30 INFO  [http-nio-8080-exec-6] c.e.o.service.UserService [trace_id=6824e98819253932775cfc97709f99c5 span_id=e04710b56fd0b26a] - Operation succeeded | operation=delete, user.id=1

2026-11-24T15:05:31.442+05:30 INFO  [http-nio-8080-exec-7] c.e.o.service.UserService [trace_id=c7a903843cca4e90f5d87ceec35a09e7 span_id=0b1e64ce384c6112] - Operation started | operation=delete, user.id=999
2026-11-24T15:05:31.444+05:30 ERROR [http-nio-8080-exec-7] c.e.o.service.UserService [trace_id=c7a903843cca4e90f5d87ceec35a09e7 span_id=65cee471b63ded55] - Operation failed | operation=delete, user.id=999, error=User not found with id: 999
```

---

# 4. Troubleshooting Exercise (Failure Analysis)

Examination of failed request:
```bash
curl -i -X DELETE http://localhost:8080/users/999
```
**HTTP Response Status:** `404 Not Found`

### Question 1: Which operation failed?
* **Operation:** `delete-user` (HTTP `DELETE /users/{id}`)
* **Entity Identifier:** `user.id = 999`
* **Request URL:** `http://localhost:8080/users/999`

### Question 2: Where did it fail?
* **Failing Layer:** Service Layer (`UserService.java`)
* **Failing Method:** `deleteUser(Long id)`
* **Exact Code Location:** Line 94 in `UserService.java`:
  ```java
  User user = userRepository.findById(id)
      .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
  ```
* **Root Cause Analysis:** `userRepository.findById(999L)` returned an empty `Optional` because no user record exists with ID `999`. The service raised a `UserNotFoundException`, preventing `userRepository.delete()` from executing. Spring's `GlobalExceptionHandler` caught the exception and generated the HTTP 404 response.

### Question 3: How is the error represented in the trace?
* **Trace ID:** `c7a903843cca4e90f5d87ceec35a09e7`
* **Root Server Span (`65cee471b63ded55`):** Standard Tomcat server span recorded attribute `http.response.status_code = 404`.
* **Child Service Span (`0b1e64ce384c6112`):**
  * Status set to `StatusCode.ERROR` with description `"User not found with id: 999"`.
  * OpenTelemetry exception event recorded with type `com.example.observability.exception.UserNotFoundException` and full stack trace.
  * Preserved attributes `operation.name = "delete-user"` and `user.id = 999`.

### Question 4: Which log belongs to the trace?
Both correlated logs carry `trace_id=c7a903843cca4e90f5d87ceec35a09e7`:
1. **Start Log:**
   ```text
   2026-11-24T15:05:31.442+05:30 INFO  [http-nio-8080-exec-7] c.e.o.service.UserService [trace_id=c7a903843cca4e90f5d87ceec35a09e7 span_id=0b1e64ce384c6112] - Operation started | operation=delete, user.id=999
   ```
2. **Error Log:**
   ```text
   2026-11-24T15:05:31.444+05:30 ERROR [http-nio-8080-exec-7] c.e.o.service.UserService [trace_id=c7a903843cca4e90f5d87ceec35a09e7 span_id=65cee471b63ded55] - Operation failed | operation=delete, user.id=999, error=User not found with id: 999
   ```

### Question 5: How is the failure reflected in the metrics?
1. **Operation Counter (`users.operation.count`):**
   * Counter with attributes `{operation="delete", status="error"}` incremented to `1`.
   * Exemplar captured linking the metric to `traceId=c7a903843cca4e90f5d87ceec35a09e7` and `spanId=65cee471b63ded55`.
2. **Operation Duration (`users.operation.duration`):**
   * Histogram recorded `2.0 ms` duration under `{operation="delete", status="error"}`.

---

# 5. Best Practices Review

### Provided Code Snippet:
```java
// Inside a Spring Boot service
public void updateProfile(User user) {
    Span span = tracer.spanBuilder("update-profile").startSpan();
    span.setAttribute("user.password", user.getPassword());
    database.save(user);
}
```

### Identified Violations & Recommendations

1. **Severe Security Violation (Plain-Text Credential / PII Leakage):**
   * *Problem:* `span.setAttribute("user.password", user.getPassword())` exports raw user credentials into telemetry storage, log streams, and monitoring dashboards.
   * *Fix:* Remove password attributes completely. Only record non-sensitive identifiers such as `user.id`.

2. **Critical Resource Leak (Missing `span.end()`):**
   * *Problem:* `span.startSpan()` is called, but `span.end()` is never invoked. Spans remain resident in memory indefinitely, causing severe memory leaks and preventing the span from ever being exported.
   * *Fix:* Always invoke `span.end()` inside a `finally` block.

3. **Exception Safety Failure (Missing `try-finally` Block):**
   * *Problem:* If `database.save(user)` throws an exception, unhandled code paths exit immediately, bypassing any cleanup.
   * *Fix:* Wrap all operations between `startSpan()` and `span.end()` in a `try-catch-finally` block.

4. **Missing Error Telemetry (No Error Status or Exception Event):**
   * *Problem:* When exceptions occur, the span status remains `UNSET`, masking application errors as successful operations in monitoring dashboards.
   * *Fix:* In the `catch` block, invoke `span.recordException(e)` and `span.setStatus(StatusCode.ERROR, e.getMessage())`.

5. **Context Propagation Failure (Missing `span.makeCurrent()`):**
   * *Problem:* The span is created but not activated in the thread context. Downstream operations (e.g. database queries executed by `database.save()`) will not be parented under `update-profile`.
   * *Fix:* Use `try (Scope scope = span.makeCurrent()) { ... }`.

### Refactored Code:
```java
public void updateProfile(User user) {
    Span span = tracer.spanBuilder("update-profile")
            .setAttribute("operation.name", "update-profile")
            .setAttribute("user.id", user.getId())
            .startSpan();

    try (Scope scope = span.makeCurrent()) {
        database.save(user);
    } catch (Exception e) {
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, "Profile update failed: " + e.getMessage());
        throw e;
    } finally {
        span.end();
    }
}
```

---

# 6. Knowledge Check (10 MCQs with Technical Justifications)

### Question 1: What are the three primary sources of telemetry data in OpenTelemetry?
* **Correct Answer:** **A) Logs, Metrics, Traces**
* **Technical Justification:** OpenTelemetry formalizes the "Three Pillars of Observability": Traces (request path through distributed services), Metrics (numerical aggregations over intervals), and Logs (timestamped discrete event records).

---

### Question 2: Which CNCF projects were merged to create OpenTelemetry?
* **Correct Answer:** **B) OpenTracing and OpenCensus**
* **Technical Justification:** In May 2019, the Cloud Native Computing Foundation (CNCF) unified OpenTracing (API-first distributed tracing standard) and OpenCensus (Google's libraries for metrics and tracing) to establish OpenTelemetry as a single industry standard.

---

### Question 3: What is the primary difference between the OpenTelemetry API and the SDK?
* **Correct Answer:** **C) The API defines interfaces for telemetry generation, while the SDK implements them and handles lifecycle.**
* **Technical Justification:** The OpenTelemetry API contains only interfaces and no-op implementations, allowing libraries and applications to instrument code without binding to an operational implementation. The SDK implements the API, managing processors, samplers, state buffers, batching queues, and exporters.

---

### Question 4: What is the main benefit of Zero-Code (Auto) Instrumentation?
* **Correct Answer:** **C) It allows telemetry collection without modifying application source code.**
* **Technical Justification:** Zero-code instrumentation (via agents like `-javaagent:opentelemetry-javaagent.jar`) intercepts bytecode dynamically at class-loading time, automatically capturing telemetry from popular frameworks (Tomcat, Spring, Hibernate, JDBC) without code edits or recompilation.

---

### Question 5: What is the purpose of Semantic Conventions in OpenTelemetry?
* **Correct Answer:** **B) To standardize naming rules for attributes, span names, and metrics.**
* **Technical Justification:** Semantic Conventions define consistent naming schemas across programming languages and vendors (e.g. `http.request.method`, `http.response.status_code`, `db.system`), ensuring interoperability across backends and visualization tools.

---

### Question 6: Which header is used by OpenTelemetry for distributed trace propagation following W3C standards?
* **Correct Answer:** **B) traceparent**
* **Technical Justification:** The W3C Trace Context recommendation mandates the `traceparent` header (format: `version-trace_id-parent_id-trace_flags`, e.g. `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`) for passing trace context across HTTP boundaries.

---

### Question 7: Why is the OpenTelemetry Collector recommended in production?
* **Correct Answer:** **B) It provides a vendor-neutral centralized pipeline for processing and exporting telemetry.**
* **Technical Justification:** The OpenTelemetry Collector provides an out-of-process proxy architecture to receive, scrub, batch, sample, and fan-out telemetry data to multiple monitoring backends, decoupling application runtimes from backend-specific SDKs and network requirements.

---

### Question 8: What is the primary difference between Head Sampling and Tail Sampling?
* **Correct Answer:** **A) Head Sampling happens at the start of a trace, Tail Sampling happens after the trace completes.**
* **Technical Justification:** Head Sampling evaluates whether to keep or drop a trace at the root span upon ingress (cannot anticipate whether the request will error). Tail Sampling evaluates the complete trace across all spans (typically within an OTel Collector), ensuring 100% retention of errors and high latency traces.

---

### Question 9: What is 'Baggage' in OpenTelemetry?
* **Correct Answer:** **B) A set of user-defined key-value pairs that propagate across services.**
* **Technical Justification:** Baggage is a W3C-standardized context propagation mechanism that transports user-defined key-value metadata (such as `account_id` or `datacenter`) across network boundaries alongside trace context, without populating span attributes.

---

### Question 10: Which protocol is native to OpenTelemetry and recommended for exporting data?
* **Correct Answer:** **C) OTLP**
* **Technical Justification:** OpenTelemetry Protocol (OTLP) is the native protocol designed for high-throughput encoding and delivery of traces, metrics, and logs over gRPC (HTTP/2 with Protobuf) or HTTP (Protobuf/JSON).
