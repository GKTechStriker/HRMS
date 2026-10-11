package com.hrms.security.repository;

import com.hrms.security.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
    Optional<UserSession> findBySessionId(String sessionId);

    @Query("SELECT us FROM UserSession us JOIN FETCH us.user WHERE us.logoutAt IS NULL ORDER BY us.loginAt DESC")
    List<UserSession> findAllActiveSessions();

    @Query("SELECT us FROM UserSession us JOIN FETCH us.user WHERE us.user.id = :userId ORDER BY us.loginAt DESC")
    List<UserSession> findByUserIdOrderByLoginAtDesc(@Param("userId") UUID userId);

    @Query("SELECT us FROM UserSession us JOIN FETCH us.user ORDER BY us.loginAt DESC")
    List<UserSession> findAllOrderByLoginAtDesc();
}
