package com.cloudmind.demo.repository;

import com.cloudmind.demo.entity.InviteCodeBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InviteCodeBatchRepository extends JpaRepository<InviteCodeBatch, Long> {
    List<InviteCodeBatch> findTop100ByOrderByCreatedAtDesc();
}
