package com.cloudmind.demo.repository;

import com.cloudmind.demo.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByTokenHashAndTokenType(String tokenHash, String tokenType);

    @Modifying
    @Query("""
            update AuthToken t
               set t.revokedAt = :revokedAt
             where t.familyId = :familyId
               and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") String familyId, @Param("revokedAt") Instant revokedAt);

    @Modifying
    @Query("""
            update AuthToken t
               set t.revokedAt = :revokedAt
             where t.user.id = :userId
               and t.revokedAt is null
            """)
    int revokeUserTokens(@Param("userId") Long userId, @Param("revokedAt") Instant revokedAt);

    @Modifying
    @Query("delete from AuthToken t where t.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("""
            delete from AuthToken t
             where t.expiresAt < :expiredBefore
                or (t.revokedAt is not null and t.revokedAt < :revokedBefore)
            """)
    int deleteExpiredOrOldRevoked(
            @Param("expiredBefore") Instant expiredBefore,
            @Param("revokedBefore") Instant revokedBefore
    );
}
