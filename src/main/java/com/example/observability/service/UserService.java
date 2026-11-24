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

/**
 * Service layer containing business logic for User operations.
 *
 * Configured with manual OpenTelemetry tracing for business-critical operations
 * (create-user and delete-user), while allowing framework-level HTTP operations
 * to be captured automatically by the OpenTelemetry Java Agent.
 *
 * Implements custom application metrics:
 * - users.operation.count (Counter tracking operations by type and success/error status)
 * - users.operation.duration (Histogram tracking operation execution latency)
 * In accordance with best practices, user.id is never used as a metric attribute to avoid cardinality explosion.
 *
 * Implements structured application logging correlated with active trace context (trace_id and span_id).
 */
@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private static final AttributeKey<String> ATTR_OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> ATTR_STATUS = AttributeKey.stringKey("status");

    private final UserRepository userRepository;
    private final Tracer tracer;
    private final LongCounter operationCounter;
    private final DoubleHistogram operationDurationHistogram;

    public UserService(UserRepository userRepository, Tracer tracer, Meter meter) {
        this.userRepository = userRepository;
        this.tracer = tracer;
        this.operationCounter = meter.counterBuilder("users.operation.count")
                .setDescription("Total number of user operations")
                .setUnit("{operations}")
                .build();
        this.operationDurationHistogram = meter.histogramBuilder("users.operation.duration")
                .setDescription("Duration of user operations in milliseconds")
                .setUnit("ms")
                .build();
    }

    /**
     * Create a new user.
     * Manual span 'create-user' tracks execution and attaches business attributes.
     * Records metrics for operation count and duration.
     * Emits correlated logs for start, success, and failure milestones.
     *
     * @param request request containing name and email
     * @return created user response
     */
    @Transactional
    public UserResponse createUser(UserRequest request) {
        long startTime = System.currentTimeMillis();
        String status = "success";
        Span span = tracer.spanBuilder("create-user").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("operation.name", "create-user");
            logger.info("Operation started | operation=create");

            if (userRepository.existsByEmail(request.getEmail())) {
                throw new DuplicateEmailException("Email already exists: " + request.getEmail());
            }

            User user = new User();
            user.setName(request.getName());
            user.setEmail(request.getEmail());

            User saved = userRepository.save(user);
            span.setAttribute("user.id", String.valueOf(saved.getId()));
            span.setStatus(StatusCode.OK);
            logger.info("Operation succeeded | operation=create, user.id={}", saved.getId());
            return toResponse(saved);
        } catch (Exception ex) {
            status = "error";
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            logger.error("Operation failed | operation=create, error={}", ex.getMessage());
            throw ex;
        } finally {
            span.end();
            recordMetrics("create", status, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Retrieve a user by id.
     * In accordance with assignment requirements, this endpoint relies on the
     * OpenTelemetry Java Agent for automatic HTTP/server spans. No manual HTTP span is created.
     * Records metrics for operation count and duration.
     * Emits correlated logs for start, success, and failure milestones.
     *
     * @param id user id
     * @return user response
     */
    @Transactional(readOnly = true)
    public UserResponse getUser(Long id) {
        long startTime = System.currentTimeMillis();
        String status = "success";
        logger.info("Operation started | operation=get, user.id={}", id);
        try {
            User user = userRepository.findById(id)
                    .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
            logger.info("Operation succeeded | operation=get, user.id={}", id);
            return toResponse(user);
        } catch (Exception ex) {
            status = "error";
            logger.error("Operation failed | operation=get, user.id={}, error={}", id, ex.getMessage());
            throw ex;
        } finally {
            recordMetrics("get", status, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Delete a user by id.
     * Demonstrates coexistence of automatic and manual instrumentation:
     * The top-level HTTP DELETE span is provided automatically by the Java Agent,
     * and the manual 'delete-user' span nests beneath it as an in-process child span.
     * Records metrics for operation count and duration.
     * Emits correlated logs for start, success, and failure milestones.
     *
     * @param id user id
     */
    @Transactional
    public void deleteUser(Long id) {
        long startTime = System.currentTimeMillis();
        String status = "success";
        Span span = tracer.spanBuilder("delete-user").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("operation.name", "delete-user");
            span.setAttribute("user.id", String.valueOf(id));
            logger.info("Operation started | operation=delete, user.id={}", id);

            User user = userRepository.findById(id)
                    .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
            userRepository.delete(user);
            span.setStatus(StatusCode.OK);
            logger.info("Operation succeeded | operation=delete, user.id={}", id);
        } catch (Exception ex) {
            status = "error";
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            logger.error("Operation failed | operation=delete, user.id={}, error={}", id, ex.getMessage());
            throw ex;
        } finally {
            span.end();
            recordMetrics("delete", status, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Records operation count and duration metrics with low-cardinality attributes.
     * Note: user.id is intentionally omitted to avoid high-cardinality metric explosion.
     */
    private void recordMetrics(String operation, String status, double durationMs) {
        Attributes attributes = Attributes.of(
                ATTR_OPERATION, operation,
                ATTR_STATUS, status
        );
        operationCounter.add(1, attributes);
        operationDurationHistogram.record(durationMs, attributes);
    }

    /**
     * Map entity to response DTO.
     */
    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}
