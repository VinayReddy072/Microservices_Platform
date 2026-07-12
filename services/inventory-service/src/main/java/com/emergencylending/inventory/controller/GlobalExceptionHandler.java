package com.emergencylending.inventory.controller;

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
 * Global exception handler for inventory-service.
 *
 * <p>Provides two handlers that improve on Spring's default error body:
 * <ol>
 *   <li>{@link EntityNotFoundException} → HTTP 404 with a {@code message} field.</li>
 *   <li>{@link MethodArgumentNotValidException} → HTTP 400 with a per-field
 *       {@code fieldName: errorMessage} map, giving API consumers actionable
 *       information rather than the generic Spring validation error structure.</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle EntityNotFoundException — returned when a requested entity ID
     * does not exist in the database.
     *
     * @return HTTP 404 with {@code {"message": "EquipmentItem not found: 42"}}
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
     * <p>Returns a per-field error map rather than Spring's default generic
     * validation error body. Example response:
     * <pre>
     * {
     *   "name": "Equipment name must not be blank",
     *   "category": "Category must not be blank"
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
}
