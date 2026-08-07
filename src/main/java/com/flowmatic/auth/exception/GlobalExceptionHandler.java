package com.flowmatic.auth.exception;

import com.flowmatic.auth.dto.ErrorResponse;
import com.stripe.exception.StripeException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(UserAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleUserExists(
      UserAlreadyExistsException ex, HttpServletRequest req) {
    return build(HttpStatus.CONFLICT, ex.getMessage(), req);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ErrorResponse> handleInvalidCredentials(
      InvalidCredentialsException ex, HttpServletRequest req) {
    return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req);
  }

  @ExceptionHandler(InvalidTokenException.class)
  public ResponseEntity<ErrorResponse> handleInvalidToken(
      InvalidTokenException ex, HttpServletRequest req) {
    return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req);
  }

  @ExceptionHandler(EmailNotVerifiedException.class)
  public ResponseEntity<ErrorResponse> handleEmailNotVerified(
      EmailNotVerifiedException ex, HttpServletRequest req) {
    return build(HttpStatus.FORBIDDEN, ex.getMessage(), req);
  }

  @ExceptionHandler(InvalidOtpException.class)
  public ResponseEntity<ErrorResponse> handleInvalidOtp(
      InvalidOtpException ex, HttpServletRequest req) {
    return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
  }

  @ExceptionHandler(OtpResendCooldownException.class)
  public ResponseEntity<ErrorResponse> handleOtpCooldown(
      OtpResendCooldownException ex, HttpServletRequest req) {
    return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req);
  }

  /** Stripe is an upstream dependency; its failures are a gateway fault, not ours. */
  @ExceptionHandler(StripeException.class)
  public ResponseEntity<ErrorResponse> handleStripeException(
      StripeException ex, HttpServletRequest req) {
    log.error("Stripe API error on {} {}", req.getMethod(), req.getRequestURI(), ex);
    return build(HttpStatus.BAD_GATEWAY, "Payment provider is temporarily unavailable", req);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest req) {
    return build(HttpStatus.FORBIDDEN, "You do not have permission to access this resource", req);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest req) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
    return build(HttpStatus.BAD_REQUEST, message, req);
  }

  /**
   * Thrown when the request body fails to deserialize (e.g. an unrecognized enum value) — happens
   * before the controller method runs, so it's a client error, not a server fault.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMalformedBody(
      HttpMessageNotReadableException ex, HttpServletRequest req) {
    return build(HttpStatus.BAD_REQUEST, "Malformed request body", req);
  }

  @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
  public ResponseEntity<ErrorResponse> handleMultipart(Exception ex, HttpServletRequest req) {
    return build(
        HttpStatus.BAD_REQUEST,
        "Expected a multipart/form-data upload with a 'file' part: " + ex.getMessage(),
        req);
  }

  /**
   * Statuses a controller set deliberately. Without this, the catch-all below would win — {@code
   * ExceptionHandlerExceptionResolver} runs before {@code ResponseStatusExceptionResolver}, so
   * every intended 404/400 would leave as an opaque 500.
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest req) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    return build(status, ex.getReason() != null ? ex.getReason() : status.getReasonPhrase(), req);
  }

  /** A constraint violation is a conflict, not a server fault. Cause is logged, not returned. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> handleDataIntegrity(
      DataIntegrityViolationException ex, HttpServletRequest req) {
    log.warn("Data integrity violation on {} {}", req.getMethod(), req.getRequestURI(), ex);
    return build(HttpStatus.CONFLICT, "Request conflicts with existing data", req);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
    // Spring's own MVC exceptions carry their status via ErrorResponse (unknown path -> 404, wrong
    // verb -> 405, unparseable path variable -> 400). Honour it rather than flattening them to 500.
    if (ex instanceof org.springframework.web.ErrorResponse errorResponse) {
      HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
      log.warn(
          "{} on {} {}: {}", status.value(), req.getMethod(), req.getRequestURI(), ex.getMessage());
      String detail = errorResponse.getBody().getDetail();
      return build(status, detail != null ? detail : status.getReasonPhrase(), req);
    }
    // Log the real cause; an opaque 500 with no stack trace is undebuggable.
    log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req);
  }

  private ResponseEntity<ErrorResponse> build(
      HttpStatus status, String message, HttpServletRequest req) {
    ErrorResponse body =
        ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(status.value())
            .error(status.getReasonPhrase())
            .message(message)
            .path(req.getRequestURI())
            .build();
    return ResponseEntity.status(status).body(body);
  }
}
