package com.movierec.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** API errors are {"detail": ...}, the shape the front-end reads, with FastAPI's status codes. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static ResponseEntity<Object> detail(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("detail", message));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> statusError(ResponseStatusException error) {
        HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
        // The FastAPI app's 404 handler reports every API 404 with this one message.
        return detail(status, status == HttpStatus.NOT_FOUND ? "not found" : error.getReason());
    }

    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<Object> badInput(Exception error) {
        return detail(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> wrongMethod() {
        return detail(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
    }

    /** Unknown API paths are a JSON 404; anything else gets the single-page app. */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Object> notFound(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/")) {
            return detail(HttpStatus.NOT_FOUND, "not found");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(new ClassPathResource("static/index.html"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception error) {
        error.printStackTrace();
        return detail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
    }
}
