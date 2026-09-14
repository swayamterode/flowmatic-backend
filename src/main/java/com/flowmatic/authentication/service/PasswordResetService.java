package com.flowmatic.authentication.service;

import com.flowmatic.authentication.dto.ForgotPasswordRequest;
import com.flowmatic.common.dto.MessageResponse;
import com.flowmatic.authentication.dto.ResetPasswordRequest;

public interface PasswordResetService {
  MessageResponse requestReset(ForgotPasswordRequest request);

  MessageResponse resetPassword(ResetPasswordRequest request);
}
