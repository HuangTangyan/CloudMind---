package com.cloudmind.demo.repository;

import com.cloudmind.demo.entity.InviteAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InviteAuditLogRepository extends JpaRepository<InviteAuditLog, Long> {
    List<InviteAuditLog> findTop100ByOrderByCreatedAtDesc();
}
