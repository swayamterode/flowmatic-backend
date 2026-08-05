package com.flowmatic.auth.repository;

import com.flowmatic.auth.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  /**
   * Atomically increments {@code workflowRunCount} only if it's still under {@code limit},
   * returning the number of rows updated (1 if it incremented, 0 if the user was already at the
   * cap). The row lock this UPDATE takes serializes concurrent calls for the same user, so two
   * simultaneous requests at count=limit-1 can never both succeed.
   */
  @Modifying
  @Query(
      "update User u set u.workflowRunCount = coalesce(u.workflowRunCount, 0) + 1 "
          + "where u.id = :id and coalesce(u.workflowRunCount, 0) < :limit")
  int incrementRunCountIfUnderLimit(@Param("id") Long id, @Param("limit") int limit);
}
