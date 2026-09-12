package com.intellidesk.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Redis distributed lock.
 */
@ConfigurationProperties(prefix = "intellidesk.distributed-lock")
public class DistributedLockProperties {

    /**
     * Default lease TTL in milliseconds. Must be > 0.
     */
    private int leaseMs = 30_000;

    /**
     * Redis key namespace prefix.
     */
    private String namespace = "intellidesk:dev";

    @PostConstruct
    public void validate() {
        if (leaseMs <= 0) {
            throw new IllegalStateException("distributed-lock.lease-ms must be > 0");
        }
    }

    public int getLeaseMs() {
        return leaseMs;
    }

    public void setLeaseMs(int leaseMs) {
        this.leaseMs = leaseMs;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String buildKey(String resource) {
        return namespace + ":lock:" + resource;
    }
}