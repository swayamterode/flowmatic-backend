package com.flowmatic.auth.workflow.repository;

import com.flowmatic.auth.workflow.entity.UserIntegration;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIntegrationRepository extends JpaRepository<UserIntegration, Long> {

  Optional<UserIntegration> findByUser_IdAndProvider(Long userId, String provider);
}
