package com.flowmatic.authentication.service;

import com.flowmatic.authentication.dto.ForgotPasswordRequest;
import com.flowmatic.authentication.dto.ResetPasswordRequest;
import com.flowmatic.common.dto.MessageResponse;

public interface PasswordResetService {
  MessageResponse requestReset(ForgotPasswordRequest request);

  MessageResponse resetPassword(ResetPasswordRequest request);
}
