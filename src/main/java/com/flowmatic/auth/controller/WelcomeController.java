package com.flowmatic.auth.controller;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WelcomeController {

  @GetMapping("/")
  public ResponseEntity<Map<String, Object>> welcome() {
    return ResponseEntity.ok(
        Map.of(
            "message", "Welcome to the FlowMatic Auth service",
            "status", "UP",
            "timestamp", Instant.now()));
  }
}
