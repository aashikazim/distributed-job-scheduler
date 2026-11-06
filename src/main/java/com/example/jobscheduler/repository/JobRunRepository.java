package com.example.jobscheduler.repository;

import java.util.UUID;

import com.example.jobscheduler.domain.JobRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRunRepository extends JpaRepository<JobRun, UUID> {
    Page<JobRun> findByJobIdOrderByStartedAtDesc(UUID jobId, Pageable pageable);
}
