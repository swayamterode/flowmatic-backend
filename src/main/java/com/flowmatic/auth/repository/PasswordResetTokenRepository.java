package com.flowmatic.auth.repository;

import com.flowmatic.auth.entity.PasswordResetToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

  Optional<PasswordResetToken> findByEmail(String email);

  Optional<PasswordResetToken> findByTokenHash(String tokenHash);
}
