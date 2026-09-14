package com.flowmatic.authentication.repository;

import com.flowmatic.authentication.entity.PasswordResetToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

  Optional<PasswordResetToken> findByEmail(String email);

  Optional<PasswordResetToken> findByTokenHash(String tokenHash);
}
