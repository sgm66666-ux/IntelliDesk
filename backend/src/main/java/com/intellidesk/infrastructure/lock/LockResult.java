package com.intellidesk.infrastructure.lock;

/**
 * Result of a distributed lock acquisition attempt.
 * <p>
 * Explicitly distinguishes three states:
 * <ul>
 *   <li>ACQUIRED — lock was successfully obtained</li>
 *   <li>CONTENDED — another owner holds the lock (normal contention)</li>
 *   <li>INFRA_FAILURE — Redis connection/timeout/infrastructure failure</li>
 * </ul>
 * Never uses a boolean that conflates "someone else holds the lock" with "Redis is down".
 */
public enum LockResult {
    ACQUIRED,
    CONTENDED,
    INFRA_FAILURE
}