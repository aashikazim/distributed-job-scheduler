package com.example.jobscheduler.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** Update only edited fields so API writes cannot restore an older heartbeat lease. */
@Entity
@DynamicUpdate
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200, unique = true)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 50)
    private String jobType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleType scheduleType;

    @Column(length = 100)
    private String cronExpression;

    private Integer fixedRateSeconds;

    private Instant runAt;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status = JobStatus.ACTIVE;

    private Instant nextRunAt;

    private Instant lastRunAt;

    @Column(nullable = false)
    private int maxRetries = 3;

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(nullable = false)
    private int backoffInitialSecs = 10;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal backoffMultiplier = new BigDecimal("2.00");

    @Column(nullable = false)
    private int backoffMaxSecs = 3600;

    @Column(nullable = false)
    private int timeoutSeconds = 60;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MisfirePolicy misfirePolicy = MisfirePolicy.FIRE_ONCE_NOW;

    @Column(length = 100)
    private String lockedBy;

    private Instant lockedUntil;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp(source = SourceType.DB)
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(nullable = false)
    private Instant updatedAt;

    protected Job() {
    }

    public Job(String name, String jobType, JsonNode payload, ScheduleType scheduleType) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.jobType = jobType;
        this.payload = payload;
        this.scheduleType = scheduleType;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    public ScheduleType getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(ScheduleType scheduleType) {
        this.scheduleType = scheduleType;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public Integer getFixedRateSeconds() {
        return fixedRateSeconds;
    }

    public void setFixedRateSeconds(Integer fixedRateSeconds) {
        this.fixedRateSeconds = fixedRateSeconds;
    }

    public Instant getRunAt() {
        return runAt;
    }

    public void setRunAt(Instant runAt) {
        this.runAt = runAt;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Instant lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getBackoffInitialSecs() {
        return backoffInitialSecs;
    }

    public void setBackoffInitialSecs(int backoffInitialSecs) {
        this.backoffInitialSecs = backoffInitialSecs;
    }

    public BigDecimal getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(BigDecimal backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public int getBackoffMaxSecs() {
        return backoffMaxSecs;
    }

    public void setBackoffMaxSecs(int backoffMaxSecs) {
        this.backoffMaxSecs = backoffMaxSecs;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public MisfirePolicy getMisfirePolicy() {
        return misfirePolicy;
    }

    public void setMisfirePolicy(MisfirePolicy misfirePolicy) {
        this.misfirePolicy = misfirePolicy;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
