package com.example.jobscheduler.api;

import java.util.UUID;

import com.example.jobscheduler.service.RunHistoryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.context.annotation.Conditional(com.example.jobscheduler.config.RoleConditions.Api.class)
@RequestMapping("/api/v1")
@Validated
public class RunHistoryController {
    private final RunHistoryService history;

    public RunHistoryController(RunHistoryService history) {
        this.history = history;
    }

    @GetMapping("/jobs/{id}/runs")
    public PageResponse<JobRunResponse> list(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return history.list(id, page, size);
    }

    @GetMapping("/runs/{id}")
    public JobRunResponse get(@PathVariable UUID id) {
        return history.get(id);
    }
}
