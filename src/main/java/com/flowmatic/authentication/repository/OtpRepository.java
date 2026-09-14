package com.flowmatic.authentication.repository;

import com.flowmatic.authentication.entity.EmailOtp;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtpRepository extends JpaRepository<EmailOtp, Long> {
  Optional<EmailOtp> findByEmail(String email);
}
