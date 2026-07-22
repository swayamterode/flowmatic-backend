package com.flowmatic.auth.repository;

import com.flowmatic.auth.entity.EmailOtp;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtpRepository extends JpaRepository<EmailOtp, Long> {
  Optional<EmailOtp> findByEmail(String email);
}
