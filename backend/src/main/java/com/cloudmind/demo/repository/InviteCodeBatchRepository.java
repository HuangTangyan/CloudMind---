package com.cloudmind.demo.repository;

import com.cloudmind.demo.entity.InviteCodeBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InviteCodeBatchRepository extends JpaRepository<InviteCodeBatch, Long> {
    List<InviteCodeBatch> findTop100ByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select batch from InviteCodeBatch batch where batch.id = :id")
    Optional<InviteCodeBatch> findForUpdateById(@Param("id") Long id);

    @Query("""
            select coalesce(sum(batch.totalCount), 0)
              from InviteCodeBatch batch
             where batch.createdBy.id = :adminId
               and batch.createdAt >= :start
               and batch.createdAt < :end
            """)
    long sumGeneratedByAdminBetween(
            @Param("adminId") Long adminId,
            @Param("start") Instant start,
            @Param("end") Instant end
    );
}
