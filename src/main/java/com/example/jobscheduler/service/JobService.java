package com.example.jobscheduler.service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.example.jobscheduler.api.ApiException;
import com.example.jobscheduler.api.JobRequest;
import com.example.jobscheduler.api.JobResponse;
import com.example.jobscheduler.api.PageResponse;
import com.example.jobscheduler.domain.Job;
import com.example.jobscheduler.domain.JobStatus;
import com.example.jobscheduler.executor.HttpPayloadValidator;
import com.example.jobscheduler.repository.JobRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class JobService {
    private final JobRepository jobs;
    private final ScheduleCalculator schedules;
    private final HttpPayloadValidator payloads;
    private final Clock clock;
    private final JdbcClient jdbc;

    public JobService(JobRepository jobs, ScheduleCalculator schedules, HttpPayloadValidator payloads,
                      Clock clock, JdbcClient jdbc) {
        this.jobs = jobs;
        this.schedules = schedules;
        this.payloads = payloads;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    public JobResponse create(JobRequest request) {
        if (request.version() != null) {
            throw badVersion("Omit version when creating a job");
        }
        validate(request);
        var job = new Job(request.name(), request.jobType(), request.payload(), request.scheduleType());
        apply(job, request);
        job.setNextRunAt(next(job));
        return JobResponse.from(jobs.saveAndFlush(job));
    }

    @Transactional(readOnly = true)
    public JobResponse get(UUID id) {
        return JobResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<JobResponse> list(int page, int size, JobStatus status, String type, String name) {
        Specification<Job> filter = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("jobType"), type));
            }
            if (name != null) {
                var escaped = name.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                        .replace("%", "\\%").replace("_", "\\_");
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + escaped + "%", '\\'));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.from(jobs.findAll(filter,
                PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id"))))
                .map(JobResponse::from));
    }

    public JobResponse update(UUID id, JobRequest request) {
        var job = find(id);
        if (request.version() == null) {
            throw badVersion("version is required for PUT");
        }
        if (!request.version().equals(job.getVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "Job changed; fetch the latest version and retry");
        }
        validate(request);
        boolean scheduleChanged = job.getScheduleType() != request.scheduleType()
                || !Objects.equals(job.getCronExpression(), request.cronExpression())
                || !Objects.equals(job.getFixedRateSeconds(), request.fixedRateSeconds())
                || !Objects.equals(job.getRunAt(), request.runAt())
                || !Objects.equals(job.getTimezone(), request.timezone());
        apply(job, request);
        if (scheduleChanged) {
            job.setNextRunAt(next(job));
            job.setRetryCount(0);
        }
        return JobResponse.from(jobs.saveAndFlush(job));
    }

    public void delete(UUID id) {
        jobs.delete(find(id));
        jobs.flush();
    }

    public JobResponse pause(UUID id) {
        var job = find(id);
        if (job.getStatus() != JobStatus.PAUSED) {
            job.setStatus(JobStatus.PAUSED);
            jobs.flush();
        }
        return JobResponse.from(job);
    }

    public JobResponse resume(UUID id) {
        var job = find(id);
        job.setStatus(JobStatus.ACTIVE);
        job.setRetryCount(0);
        job.setNextRunAt(next(job));
        jobs.flush();
        return JobResponse.from(job);
    }

    /** Trigger only changes durable state; a later worker claims the job asynchronously. */
    public JobResponse trigger(UUID id) {
        var job = find(id);
        Instant now = jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
        job.setStatus(JobStatus.ACTIVE);
        job.setRetryCount(0);
        job.setNextRunAt(now);
        jobs.flush();
        return JobResponse.from(job);
    }

    private Job find(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    private void validate(JobRequest request) {
        schedules.validate(request);
        payloads.validate(request.jobType(), request.payload());
    }

    private Instant next(Job job) {
        return schedules.next(job.getScheduleType(), job.getCronExpression(), job.getFixedRateSeconds(),
                job.getRunAt(), job.getTimezone(), clock.instant());
    }

    private ApiException badVersion(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of("version", message));
    }

    private void apply(Job job, JobRequest request) {
        job.setName(request.name());
        job.setDescription(request.description());
        job.setJobType(request.jobType());
        job.setPayload(request.payload());
        job.setScheduleType(request.scheduleType());
        job.setCronExpression(request.cronExpression());
        job.setFixedRateSeconds(request.fixedRateSeconds());
        job.setRunAt(request.runAt());
        job.setTimezone(request.timezone());
        job.setMaxRetries(request.maxRetries());
        job.setBackoffInitialSecs(request.backoffInitialSecs());
        job.setBackoffMultiplier(request.backoffMultiplier());
        job.setBackoffMaxSecs(request.backoffMaxSecs());
        job.setTimeoutSeconds(request.timeoutSeconds());
        job.setMisfirePolicy(request.misfirePolicy());
    }
}
