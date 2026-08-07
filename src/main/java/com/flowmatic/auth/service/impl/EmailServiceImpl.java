package com.flowmatic.auth.service.impl;

import com.flowmatic.auth.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

  private static final String SUBJECT = "Your FlowMatic verification code";
  private static final String RESET_SUBJECT = "Reset your FlowMatic password";

  private final JavaMailSender mailSender;

  @Value("${app.mail.from}")
  private String fromAddress;

  @Value("${app.otp.expiry-minutes}")
  private long expiryMinutes;

  @Value("${app.password-reset.expiry-minutes:30}")
  private long resetLinkExpiryMinutes;

  // @Async so SMTP latency never blocks the HTTP request thread.
  @Async
  @Override
  public void sendOtpEmail(String to, String code) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      // multipart/alternative: HTML for capable clients, plain text as the fallback part.
      MimeMessageHelper helper =
          new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
      helper.setFrom(fromAddress);
      helper.setTo(to);
      helper.setSubject(SUBJECT);

      helper.setText(buildPlainText(code), buildHtml(code));
      mailSender.send(message);
    } catch (MessagingException | MailException ex) {
      log.error("Failed to send OTP email to {}", to, ex);
    }
  }

  // @Async so SMTP latency never blocks the HTTP request thread.
  @Async
  @Override
  public void sendPasswordResetEmail(String to, String resetLink) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper =
          new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
      helper.setFrom(fromAddress);
      helper.setTo(to);
      helper.setSubject(RESET_SUBJECT);

      helper.setText(buildResetPlainText(resetLink), buildResetHtml(resetLink));
      mailSender.send(message);
    } catch (MessagingException | MailException ex) {
      log.error("Failed to send password reset email to {}", to, ex);
    }
  }

  // Fallback for clients that don't render HTML.
  private String buildPlainText(String code) {
    return "Your FlowMatic verification code is: "
        + code
        + "\n\n"
        + "This code expires in "
        + expiryMinutes
        + " minutes.\n"
        + "If you didn't request it, you can safely ignore this email.\n\n"
        + "— The FlowMatic team";
  }

  private String buildHtml(String code) {
    String preheader =
        "Your verification code is " + code + " — it expires in " + expiryMinutes + " minutes.";

    return """
<!DOCTYPE html>
<html lang="en" xmlns="http://www.w3.org/1999/xhtml">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta name="x-apple-disable-message-reformatting">
  <meta http-equiv="X-UA-Compatible" content="IE=edge">
  <title>%SUBJECT%</title>
  <style>
    body { margin: 0; padding: 0; width: 100% !important; -webkit-text-size-adjust: 100%; -ms-text-size-adjust: 100%; }
    table { border-collapse: collapse; }
    img { border: 0; line-height: 100%; outline: none; text-decoration: none; -ms-interpolation-mode: bicubic; }
    a { text-decoration: none; }
    @media (prefers-color-scheme: dark) {
      .fm-page { background-color: #0B1220 !important; }
    }
  </style>
</head>
<body style="margin:0; padding:0; background-color:#F4F5F7;">

  <!-- Preheader: shown in inbox preview, hidden in the body -->
  <div style="display:none; max-height:0; overflow:hidden; mso-hide:all; font-size:1px; line-height:1px; color:#F4F5F7; opacity:0;">
    %PREHEADER%&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;
  </div>

  <table role="presentation" class="fm-page" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#F4F5F7;">
    <tr>
      <td align="center" style="padding:40px 16px;">

        <!-- Card -->
        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:600px; max-width:600px; background-color:#FFFFFF; border:1px solid #E6E8EC; border-radius:14px; overflow:hidden;">

          <!-- Flow bar (indigo -> cyan). Solid bgcolor fallback for Outlook. -->
          <tr>
            <td height="4" bgcolor="#4F46E5" style="height:4px; line-height:4px; font-size:0; background-color:#4F46E5; background-image:linear-gradient(90deg,#4F46E5 0%,#06B6D4 100%);">&nbsp;</td>
          </tr>

          <!-- Wordmark -->
          <tr>
            <td style="padding:32px 40px 8px 40px;" align="left">
              <span style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:20px; font-weight:700; letter-spacing:-0.4px; color:#0B1220;">Flow<span style="color:#4F46E5;">Matic</span></span>
            </td>
          </tr>

          <!-- Body -->
          <tr>
            <td style="padding:16px 40px 8px 40px;" align="left">
              <h1 style="margin:0 0 12px 0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:24px; line-height:32px; font-weight:700; letter-spacing:-0.5px; color:#0B1220;">Verify your email</h1>
              <p style="margin:0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:15px; line-height:24px; color:#4A5568;">Enter this code to finish signing in. It keeps your account secure.</p>
            </td>
          </tr>

          <!-- Code chip -->
          <tr>
            <td style="padding:24px 40px 8px 40px;" align="center">
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="width:100%;">
                <tr>
                  <td align="center" style="background-color:#F7F8FB; border:1px solid #E6E8EC; border-radius:12px; padding:22px 16px;">
                    <div style="font-family:'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace; font-size:38px; line-height:44px; font-weight:700; letter-spacing:10px; color:#0B1220; padding-left:10px;">%CODE%</div>
                  </td>
                </tr>
              </table>
            </td>
          </tr>

          <!-- Expiry note -->
          <tr>
            <td style="padding:12px 40px 0 40px;" align="center">
              <p style="margin:0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:13px; line-height:20px; color:#8A94A6;">This code expires in <span style="color:#4A5568; font-weight:600;">%EXPIRY% minutes</span>.</p>
            </td>
          </tr>

          <!-- Divider -->
          <tr>
            <td style="padding:28px 40px 0 40px;">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr><td height="1" style="height:1px; line-height:1px; font-size:0; background-color:#EEF0F3;">&nbsp;</td></tr></table>
            </td>
          </tr>

          <!-- Security note -->
          <tr>
            <td style="padding:20px 40px 32px 40px;" align="left">
              <p style="margin:0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:13px; line-height:20px; color:#8A94A6;">Didn't try to sign in? You can safely ignore this email — your account stays protected and no changes are made.</p>
            </td>
          </tr>

        </table>

        <!-- Footer -->
        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:600px; max-width:600px;">
          <tr>
            <td style="padding:24px 40px;" align="center">
              <p style="margin:0 0 4px 0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:12px; line-height:18px; color:#A0A8B8;">Sent by FlowMatic · This is an automated message, please don't reply.</p>
              <p style="margin:0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:12px; line-height:18px; color:#A0A8B8;">&copy; FlowMatic</p>
            </td>
          </tr>
        </table>

      </td>
    </tr>
  </table>

</body>
</html>
"""
        .replace("%SUBJECT%", SUBJECT)
        .replace("%PREHEADER%", preheader)
        .replace("%CODE%", code)
        .replace("%EXPIRY%", String.valueOf(expiryMinutes));
  }

  private String buildResetPlainText(String resetLink) {
    return "We received a request to reset your FlowMatic password.\n\n"
        + "Reset it here: "
        + resetLink
        + "\n\n"
        + "This link expires in "
        + resetLinkExpiryMinutes
        + " minutes.\n"
        + "If you didn't request this, you can safely ignore this email — your password will "
        + "not be changed.\n\n"
        + "— The FlowMatic team";
  }

  private String buildResetHtml(String resetLink) {
    String preheader =
        "Reset your password — this link expires in " + resetLinkExpiryMinutes + " minutes.";

    return """
<!DOCTYPE html>
<html lang="en" xmlns="http://www.w3.org/1999/xhtml">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta name="x-apple-disable-message-reformatting">
  <meta http-equiv="X-UA-Compatible" content="IE=edge">
  <title>%SUBJECT%</title>
  <style>
    body { margin: 0; padding: 0; width: 100% !important; -webkit-text-size-adjust: 100%; -ms-text-size-adjust: 100%; }
    table { border-collapse: collapse; }
    img { border: 0; line-height: 100%; outline: none; text-decoration: none; -ms-interpolation-mode: bicubic; }
    a { text-decoration: none; }
    @media (prefers-color-scheme: dark) {
      .fm-page { background-color: #0B1220 !important; }
    }
  </style>
</head>
<body style="margin:0; padding:0; background-color:#F4F5F7;">

  <!-- Preheader: shown in inbox preview, hidden in the body -->
  <div style="display:none; max-height:0; overflow:hidden; mso-hide:all; font-size:1px; line-height:1px; color:#F4F5F7; opacity:0;">
    %PREHEADER%&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;
  </div>

  <table role="presentation" class="fm-page" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#F4F5F7;">
    <tr>
      <td align="center" style="padding:40px 16px;">

        <!-- Card -->
        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:600px; max-width:600px; background-color:#FFFFFF; border:1px solid #E6E8EC; border-radius:14px; overflow:hidden;">

          <!-- Flow bar (indigo -> cyan). Solid bgcolor fallback for Outlook. -->
          <tr>
            <td height="4" bgcolor="#4F46E5" style="height:4px; line-height:4px; font-size:0; background-color:#4F46E5; background-image:linear-gradient(90deg,#4F46E5 0%,#06B6D4 100%);">&nbsp;</td>
          </tr>

          <!-- Wordmark -->
          <tr>
            <td style="padding:32px 40px 8px 40px;" align="left">
              <span style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:20px; font-weight:700; letter-spacing:-0.4px; color:#0B1220;">Flow<span style="color:#4F46E5;">Matic</span></span>
            </td>
          </tr>

          <!-- Body -->
          <tr>
            <td style="padding:16px 40px 8px 40px;" align="left">
              <h1 style="margin:0 0 12px 0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:24px; line-height:32px; font-weight:700; letter-spacing:-0.5px; color:#0B1220;">Reset your password</h1>
              <p style="margin:0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:15px; line-height:24px; color:#4A5568;">Click the button below to choose a new password.</p>
            </td>
          </tr>

          <!-- Reset button -->
          <tr>
            <td style="padding:24px 40px 8px 40px;" align="center">
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="width:100%;">
                <tr>
                  <td align="center" style="background-color:#4F46E5; border-radius:10px;">
                    <a href="%LINK%" target="_blank" style="display:inline-block; padding:14px 32px; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:16px; font-weight:600; color:#FFFFFF; text-decoration:none;">Reset password</a>
                  </td>
                </tr>
              </table>
            </td>
          </tr>

          <!-- Expiry note -->
          <tr>
            <td style="padding:12px 40px 0 40px;" align="center">
              <p style="margin:0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:13px; line-height:20px; color:#8A94A6;">This link expires in <span style="color:#4A5568; font-weight:600;">%EXPIRY% minutes</span>.</p>
            </td>
          </tr>

          <!-- Divider -->
          <tr>
            <td style="padding:28px 40px 0 40px;">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr><td height="1" style="height:1px; line-height:1px; font-size:0; background-color:#EEF0F3;">&nbsp;</td></tr></table>
            </td>
          </tr>

          <!-- Security note -->
          <tr>
            <td style="padding:20px 40px 32px 40px;" align="left">
              <p style="margin:0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:13px; line-height:20px; color:#8A94A6;">Didn't request a password reset? You can safely ignore this email — your password stays unchanged.</p>
            </td>
          </tr>

        </table>

        <!-- Footer -->
        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:600px; max-width:600px;">
          <tr>
            <td style="padding:24px 40px;" align="center">
              <p style="margin:0 0 4px 0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:12px; line-height:18px; color:#A0A8B8;">Sent by FlowMatic · This is an automated message, please don't reply.</p>
              <p style="margin:0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; font-size:12px; line-height:18px; color:#A0A8B8;">&copy; FlowMatic</p>
            </td>
          </tr>
        </table>

      </td>
    </tr>
  </table>

</body>
</html>
"""
        .replace("%SUBJECT%", RESET_SUBJECT)
        .replace("%PREHEADER%", preheader)
        .replace("%LINK%", resetLink)
        .replace("%EXPIRY%", String.valueOf(resetLinkExpiryMinutes));
  }
}
