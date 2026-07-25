package com.cloudmind.demo.repository;

import com.cloudmind.demo.entity.InviteCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {
    boolean existsByCodeHash(String codeHash);

    long countByBatch_IdAndRedeemedAtIsNotNull(Long batchId);

    long countByBatch_IdAndRevokedAtIsNotNull(Long batchId);

    List<InviteCode> findTop200ByBatch_IdOrderByIdAsc(Long batchId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InviteCode code
               set code.revokedAt = :revokedAt,
                   code.revokedBy = :revokedBy,
                   code.revokeReason = :reason
             where code.batch.id = :batchId
               and code.redeemedAt is null
               and code.revokedAt is null
            """)
    int revokeUnusedByBatch(
            @Param("batchId") Long batchId,
            @Param("revokedAt") java.time.Instant revokedAt,
            @Param("revokedBy") com.cloudmind.demo.entity.AppUser revokedBy,
            @Param("reason") String reason
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select code
              from InviteCode code
              join fetch code.batch
             where code.codeHash = :codeHash
            """)
    Optional<InviteCode> findForUpdateByCodeHash(@Param("codeHash") String codeHash);
}
