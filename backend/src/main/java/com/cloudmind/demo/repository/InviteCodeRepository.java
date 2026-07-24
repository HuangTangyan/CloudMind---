package com.cloudmind.demo.repository;

import com.cloudmind.demo.entity.InviteCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {
    boolean existsByCodeHash(String codeHash);

    long countByBatch_IdAndRedeemedAtIsNotNull(Long batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select code
              from InviteCode code
              join fetch code.batch
             where code.codeHash = :codeHash
            """)
    Optional<InviteCode> findForUpdateByCodeHash(@Param("codeHash") String codeHash);
}
