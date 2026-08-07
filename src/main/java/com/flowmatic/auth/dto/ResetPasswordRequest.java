package com.flowmatic.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {

  @NotBlank private String token;

  @NotBlank @Size(min = 8, max = 72) private String newPassword;
}
