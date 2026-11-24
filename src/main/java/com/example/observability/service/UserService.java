package com.example.observability.service;

import com.example.observability.dto.UserRequest;
import com.example.observability.dto.UserResponse;
import com.example.observability.entity.User;
import com.example.observability.exception.DuplicateEmailException;
import com.example.observability.exception.UserNotFoundException;
import com.example.observability.repository.UserRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service layer containing business logic for User operations.
 *
 * Configured with manual OpenTelemetry tracing for business-critical operations
 * (create-user and delete-user), while allowing framework-level HTTP operations
 * to be captured automatically by the OpenTelemetry Java Agent.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final Tracer tracer;

    public UserService(UserRepository userRepository, Tracer tracer) {
        this.userRepository = userRepository;
        this.tracer = tracer;
    }

    /**
     * Create a new user.
     * Manual span 'create-user' tracks execution and attaches business attributes.
     *
     * @param request request containing name and email
     * @return created user response
     */
    @Transactional
    public UserResponse createUser(UserRequest request) {
        Span span = tracer.spanBuilder("create-user").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("operation.name", "create-user");

            if (userRepository.existsByEmail(request.getEmail())) {
                throw new DuplicateEmailException("Email already exists: " + request.getEmail());
            }

            User user = new User();
            user.setName(request.getName());
            user.setEmail(request.getEmail());

            User saved = userRepository.save(user);
            span.setAttribute("user.id", String.valueOf(saved.getId()));
            span.setStatus(StatusCode.OK);
            return toResponse(saved);
        } catch (Exception ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Retrieve a user by id.
     * In accordance with assignment requirements, this endpoint relies on the
     * OpenTelemetry Java Agent for automatic HTTP/server spans. No manual HTTP span is created.
     *
     * @param id user id
     * @return user response
     */
    @Transactional(readOnly = true)
    public UserResponse getUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
        return toResponse(user);
    }

    /**
     * Delete a user by id.
     * Demonstrates coexistence of automatic and manual instrumentation:
     * The top-level HTTP DELETE span is provided automatically by the Java Agent,
     * and the manual 'delete-user' span nests beneath it as an in-process child span.
     *
     * @param id user id
     */
    @Transactional
    public void deleteUser(Long id) {
        Span span = tracer.spanBuilder("delete-user").startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("operation.name", "delete-user");
            span.setAttribute("user.id", String.valueOf(id));

            User user = userRepository.findById(id)
                    .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
            userRepository.delete(user);
            span.setStatus(StatusCode.OK);
        } catch (Exception ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Map entity to response DTO.
     */
    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}
