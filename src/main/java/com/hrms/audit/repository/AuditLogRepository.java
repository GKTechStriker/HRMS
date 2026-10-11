package com.hrms.audit.repository;

import com.hrms.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query("SELECT a FROM AuditLog a JOIN FETCH a.user ORDER BY a.occurredAt DESC")
    List<AuditLog> findRecentWithUser(Pageable pageable);

    @Query("SELECT a FROM AuditLog a JOIN FETCH a.user WHERE a.user.id = :userId ORDER BY a.occurredAt DESC")
    List<AuditLog> findByUserIdWithUser(@Param("userId") UUID userId, Pageable pageable);

    Page<AuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);
}
