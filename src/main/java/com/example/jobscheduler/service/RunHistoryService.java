package com.example.jobscheduler.service;

import java.util.UUID;

import com.example.jobscheduler.api.ApiException;
import com.example.jobscheduler.api.JobRunResponse;
import com.example.jobscheduler.api.PageResponse;
import com.example.jobscheduler.repository.JobRepository;
import com.example.jobscheduler.repository.JobRunRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RunHistoryService {
    private final JobRepository jobs;
    private final JobRunRepository runs;

    public RunHistoryService(JobRepository jobs, JobRunRepository runs) {
        this.jobs = jobs;
        this.runs = runs;
    }

    public PageResponse<JobRunResponse> list(UUID jobId, int page, int size) {
        if (!jobs.existsById(jobId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Job not found");
        }
        return PageResponse.from(runs.findByJobIdOrderByStartedAtDesc(jobId, PageRequest.of(page, size))
                .map(JobRunResponse::from));
    }

    public JobRunResponse get(UUID id) {
        return JobRunResponse.from(runs.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Run not found")));
    }
}
