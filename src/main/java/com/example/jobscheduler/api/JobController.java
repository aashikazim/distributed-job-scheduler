package com.example.jobscheduler.api;

import java.net.URI;
import java.util.UUID;

import com.example.jobscheduler.domain.JobStatus;
import com.example.jobscheduler.service.JobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.context.annotation.Conditional(com.example.jobscheduler.config.RoleConditions.Api.class)
@RequestMapping("/api/v1/jobs")
@Validated
public class JobController {
    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<JobResponse> create(@Valid @RequestBody JobRequest request) {
        var response = jobs.create(request);
        return ResponseEntity.created(URI.create("/api/v1/jobs/" + response.id())).body(response);
    }

    @GetMapping
    public PageResponse<JobResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String name) {
        return jobs.list(page, size, status, type, name);
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return jobs.get(id);
    }

    @PutMapping("/{id}")
    public JobResponse update(@PathVariable UUID id, @Valid @RequestBody JobRequest request) {
        return jobs.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        jobs.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    public JobResponse pause(@PathVariable UUID id) {
        return jobs.pause(id);
    }

    @PostMapping("/{id}/resume")
    public JobResponse resume(@PathVariable UUID id) {
        return jobs.resume(id);
    }

    @PostMapping("/{id}/trigger")
    public JobResponse trigger(@PathVariable UUID id) {
        return jobs.trigger(id);
    }
}
