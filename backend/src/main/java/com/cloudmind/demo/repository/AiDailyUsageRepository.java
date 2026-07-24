package com.cloudmind.demo.repository;

import com.cloudmind.demo.entity.AiDailyUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface AiDailyUsageRepository extends JpaRepository<AiDailyUsage, Long> {
    Optional<AiDailyUsage> findByUser_IdAndUsageDate(Long userId, LocalDate usageDate);
}
