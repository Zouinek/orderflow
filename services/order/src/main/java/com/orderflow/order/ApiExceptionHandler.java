package com.orderflow.order;

import org.springframework.http.*;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.exc.InvalidFormatException;


import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid (
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        FieldError::getDefaultMessage,
                        (first,second) -> first,
                        LinkedHashMap::new

                ));

        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);

        return handleExceptionInternal(ex, problem, headers, status, request);

    }

    @Override
    protected  ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (ex.getCause() instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            String message = (ife.getTargetType() == Integer.class) ? "must be a whole number" : "must be a valid number";
            String field = ife.getPath().get(0).getPropertyName();
            errors.put(field, message);
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Invalid request content");
        problem.setProperty("errors", errors);

        return handleExceptionInternal(ex, problem, headers, status, request);

    }

    @ExceptionHandler(OrderNotCancellableException.class)
    public ProblemDetail handleNotCancellable(OrderNotCancellableException ex) {
       return  ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
