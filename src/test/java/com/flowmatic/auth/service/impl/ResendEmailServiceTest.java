package com.flowmatic.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class ResendEmailServiceTest {

  private MockRestServiceServer server;
  private ResendEmailService resendEmailService;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    resendEmailService = new ResendEmailService(builder, "test-api-key", "no-reply@flowmatic.com");
  }

  @Test
  void sendPostsToResendWithBearerAuthAndTheDefaultFromAddress() {
    server
        .expect(requestTo("https://api.resend.com/emails"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-api-key"))
        .andExpect(jsonPath("$.from").value("no-reply@flowmatic.com"))
        .andExpect(jsonPath("$.to[0]").value("user@example.com"))
        .andExpect(jsonPath("$.subject").value("Hello"))
        .andExpect(jsonPath("$.text").value("hi there"))
        .andExpect(jsonPath("$.html").value("<p>hi there</p>"))
        .andRespond(withSuccess());

    resendEmailService.send("user@example.com", "Hello", "hi there", "<p>hi there</p>");

    server.verify();
  }

  @Test
  void sendWithAnExplicitFromOverridesTheDefault() {
    server
        .expect(requestTo("https://api.resend.com/emails"))
        .andExpect(jsonPath("$.from").value("sales@flowmatic.com"))
        .andRespond(withSuccess());

    resendEmailService.send("sales@flowmatic.com", "user@example.com", "Hello", "hi", null);

    server.verify();
  }

  @Test
  void omitsWhicheverBodyFieldWasNotProvided() {
    server
        .expect(requestTo("https://api.resend.com/emails"))
        .andExpect(jsonPath("$.html").value("<p>hi</p>"))
        .andExpect(jsonPath("$.text").doesNotExist())
        .andRespond(withSuccess());

    resendEmailService.send("user@example.com", "Hello", null, "<p>hi</p>");

    server.verify();
  }

  @Test
  void nonTwoXxResponsePropagatesAsARestClientException() {
    server.expect(requestTo("https://api.resend.com/emails")).andRespond(withBadRequest());

    Throwable thrown =
        catchThrowable(() -> resendEmailService.send("user@example.com", "Hello", "hi", null));

    assertThat(thrown).isInstanceOf(RestClientException.class);
  }
}
