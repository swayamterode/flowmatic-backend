package com.flowmatic.auth.service;

import com.flowmatic.auth.dto.ForgotPasswordRequest;
import com.flowmatic.auth.dto.MessageResponse;
import com.flowmatic.auth.dto.ResetPasswordRequest;

public interface PasswordResetService {
  MessageResponse requestReset(ForgotPasswordRequest request);

  MessageResponse resetPassword(ResetPasswordRequest request);
}
