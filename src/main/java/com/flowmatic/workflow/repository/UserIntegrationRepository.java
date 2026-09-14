package com.flowmatic.workflow.repository;

import com.flowmatic.workflow.entity.UserIntegration;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIntegrationRepository extends JpaRepository<UserIntegration, Long> {

  Optional<UserIntegration> findByUser_IdAndProvider(Long userId, String provider);
}
