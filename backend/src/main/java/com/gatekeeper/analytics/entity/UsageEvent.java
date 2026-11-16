package com.gatekeeper.analytics.entity;

import com.gatekeeper.apikey.entity.ApiKey;
import com.gatekeeper.consumer.entity.ApiConsumer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "usage_events")
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consumer_id")
    private ApiConsumer consumer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_key_id")
    private ApiKey apiKey;

    @Column(name = "consumer_email_snapshot", length = 150)
    private String consumerEmailSnapshot;

    @Column(name = "key_prefix_snapshot", length = 24)
    private String keyPrefixSnapshot;

    @Column(name = "endpoint_path", nullable = false, length = 255)
    private String endpointPath;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "rate_limited", nullable = false)
    private boolean rateLimited = false;

    @Column(name = "rejection_reason", length = 50)
    private String rejectionReason;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    public UsageEvent() {
    }

    public UsageEvent(String endpointPath, String httpMethod, int httpStatus, int latencyMs) {
        this.endpointPath = endpointPath;
        this.httpMethod = httpMethod;
        this.httpStatus = httpStatus;
        this.latencyMs = latencyMs;
        this.rateLimited = false;
        this.timestamp = Instant.now();
    }

    public static UsageEvent forAuthorized(ApiConsumer consumer, ApiKey apiKey, String endpointPath,
                                           String httpMethod, int httpStatus, int latencyMs) {
        UsageEvent event = new UsageEvent(endpointPath, httpMethod, httpStatus, latencyMs);
        event.setConsumer(consumer);
        event.setApiKey(apiKey);
        if (consumer != null) {
            event.setConsumerEmailSnapshot(consumer.getEmail());
        }
        if (apiKey != null) {
            event.setKeyPrefixSnapshot(apiKey.getKeyPrefix());
        }
        return event;
    }

    public static UsageEvent forRejected(String endpointPath, String httpMethod, int httpStatus,
                                         int latencyMs, boolean rateLimited, String rejectionReason,
                                         String keyPrefixSnapshot) {
        UsageEvent event = new UsageEvent(endpointPath, httpMethod, httpStatus, latencyMs);
        event.setRateLimited(rateLimited);
        event.setRejectionReason(rejectionReason);
        event.setKeyPrefixSnapshot(keyPrefixSnapshot);
        return event;
    }

    @PrePersist
    protected void onCreate() {
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public ApiConsumer getConsumer() {
        return consumer;
    }

    public void setConsumer(ApiConsumer consumer) {
        this.consumer = consumer;
    }

    public ApiKey getApiKey() {
        return apiKey;
    }

    public void setApiKey(ApiKey apiKey) {
        this.apiKey = apiKey;
    }

    public String getConsumerEmailSnapshot() {
        return consumerEmailSnapshot;
    }

    public void setConsumerEmailSnapshot(String consumerEmailSnapshot) {
        this.consumerEmailSnapshot = consumerEmailSnapshot;
    }

    public String getKeyPrefixSnapshot() {
        return keyPrefixSnapshot;
    }

    public void setKeyPrefixSnapshot(String keyPrefixSnapshot) {
        this.keyPrefixSnapshot = keyPrefixSnapshot;
    }

    public String getEndpointPath() {
        return endpointPath;
    }

    public void setEndpointPath(String endpointPath) {
        this.endpointPath = endpointPath;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(int latencyMs) {
        this.latencyMs = latencyMs;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    public void setRateLimited(boolean rateLimited) {
        this.rateLimited = rateLimited;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UsageEvent that = (UsageEvent) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
