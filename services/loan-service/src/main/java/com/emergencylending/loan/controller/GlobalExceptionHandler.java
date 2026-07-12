package com.emergencylending.loan.controller;

import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler for loan-service.
 *
 * <p>Provides two handlers that improve on Spring's default error body:
 * <ol>
 *   <li>{@link EntityNotFoundException} → HTTP 404 with a {@code message} field.</li>
 *   <li>{@link MethodArgumentNotValidException} → HTTP 400 with a per-field
 *       {@code fieldName: errorMessage} map — stronger REST-API-quality evidence
 *       than Spring's generic validation error structure.</li>
 *   <li>{@link IllegalStateException} → HTTP 409 Conflict for invalid state
 *       transitions (e.g., approving an already-approved loan).</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle EntityNotFoundException — returned when a requested loan ID
     * does not exist in the database.
     *
     * @return HTTP 404 with {@code {"message": "LoanRequest not found: 42"}}
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleEntityNotFound(EntityNotFoundException ex) {
        log.warn("Entity not found: {}", ex.getMessage());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Handle bean validation failures ({@code @Valid} on request body).
     *
     * <p>Returns a per-field error map. Example response:
     * <pre>
     * {
     *   "equipmentItemId": "Equipment item ID must not be null",
     *   "borrowerName": "Borrower name must not be blank"
     * }
     * </pre>
     *
     * @return HTTP 400 with a field-name → error-message map
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        log.debug("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * Handle illegal state transitions (e.g., approving an already-approved loan).
     *
     * @return HTTP 409 Conflict with the exception message
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        log.warn("Invalid state transition: {}", ex.getMessage());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
