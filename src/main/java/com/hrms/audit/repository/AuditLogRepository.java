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

    @Query(value = "SELECT a FROM AuditLog a JOIN FETCH a.user u " +
           "WHERE (:action IS NULL OR :action = '' OR a.action = :action) " +
           "AND (cast(:fromInstant as java.time.Instant) IS NULL OR a.occurredAt >= :fromInstant) " +
           "AND (cast(:toInstant as java.time.Instant) IS NULL OR a.occurredAt <= :toInstant) " +
           "AND (:userId IS NULL OR u.id = :userId) " +
           "ORDER BY a.occurredAt DESC",
           countQuery = "SELECT COUNT(a) FROM AuditLog a " +
           "WHERE (:action IS NULL OR :action = '' OR a.action = :action) " +
           "AND (cast(:fromInstant as java.time.Instant) IS NULL OR a.occurredAt >= :fromInstant) " +
           "AND (cast(:toInstant as java.time.Instant) IS NULL OR a.occurredAt <= :toInstant) " +
           "AND (:userId IS NULL OR a.user.id = :userId)")
    Page<AuditLog> searchAuditLogs(@Param("action") String action,
                                  @Param("fromInstant") java.time.Instant fromInstant,
                                  @Param("toInstant") java.time.Instant toInstant,
                                  @Param("userId") UUID userId,
                                  Pageable pageable);

    @Query("SELECT DISTINCT a.action FROM AuditLog a ORDER BY a.action ASC")
    List<String> findDistinctActions();
}
