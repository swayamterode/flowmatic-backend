package com.flowmatic.auth.dto;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ErrorResponse {

  private Instant timestamp;
  private int status;
  private String error;
  private String message;
  private String path;
}
