package com.flowmatic.auth.service.impl;

import com.flowmatic.auth.entity.EmailOtp;
import com.flowmatic.auth.exception.InvalidOtpException;
import com.flowmatic.auth.exception.OtpResendCooldownException;
import com.flowmatic.auth.repository.OtpRepository;
import com.flowmatic.auth.service.EmailService;
import com.flowmatic.auth.service.OtpService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OtpServiceImpl implements OtpService {

  private final OtpRepository otpRepository;
  private final EmailService emailService;
  private final PasswordEncoder passwordEncoder;
  private final SecureRandom secureRandom = new SecureRandom();

  @Value("${app.otp.length}")
  private int otpLength;

  @Value("${app.otp.expiry-minutes}")
  private long expiryMinutes;

  @Value("${app.otp.max-attempts}")
  private int maxAttempts;

  @Value("${app.otp.resend-cooldown-seconds}")
  private long resendCooldownSeconds;

  @Override
  @Transactional
  public void generateAndSend(String email) {
    issueOtp(email);
  }

  @Override
  @Transactional
  public void resend(String email) {
    otpRepository
        .findByEmail(email)
        .ifPresent(
            existing -> {
              long secondsSince =
                  Duration.between(existing.getCreatedAt(), Instant.now()).getSeconds();
              if (secondsSince < resendCooldownSeconds) {
                throw new OtpResendCooldownException(
                    "Please wait "
                        + (resendCooldownSeconds - secondsSince)
                        + " seconds before requesting another code.");
              }
            });
    issueOtp(email);
  }

  @Override
  @Transactional
  public void verify(String email, String otp) {
    EmailOtp record =
        otpRepository
            .findByEmail(email)
            .orElseThrow(() -> new InvalidOtpException("No OTP found. Please request a new code."));

    if (Instant.now().isAfter(record.getExpiresAt())) {
      otpRepository.delete(record);
      throw new InvalidOtpException("OTP has expired. Please request a new code.");
    }

    if (record.getAttempts() >= maxAttempts) {
      otpRepository.delete(record);
      throw new InvalidOtpException("Too many incorrect attempts. Please request a new code.");
    }

    if (!passwordEncoder.matches(otp, record.getOtpHash())) {
      record.setAttempts(record.getAttempts() + 1);
      otpRepository.save(record);
      throw new InvalidOtpException("Invalid OTP.");
    }

    // Success — consume the OTP so it can't be reused.
    otpRepository.delete(record);
  }

  /**
   * Upsert pattern: reuse the existing row for this email if present, otherwise create a new one.
   * We deliberately avoid delete-then-insert to sidestep Hibernate's insert-before-delete flush
   * ordering, which would violate the unique email constraint.
   */
  private void issueOtp(String email) {
    String code = generateCode();
    Instant now = Instant.now();

    EmailOtp otp = otpRepository.findByEmail(email).orElseGet(EmailOtp::new);
    otp.setEmail(email);
    otp.setOtpHash(passwordEncoder.encode(code));
    otp.setExpiresAt(now.plus(expiryMinutes, ChronoUnit.MINUTES));
    otp.setAttempts(0);
    otp.setCreatedAt(now);

    otpRepository.save(otp);
    emailService.sendOtpEmail(email, code);
  }

  private String generateCode() {
    StringBuilder sb = new StringBuilder(otpLength);
    for (int i = 0; i < otpLength; i++) {
      sb.append(secureRandom.nextInt(10));
    }
    return sb.toString();
  }
}
