package com.intellidesk.infrastructure.lock;

/**
 * Handle for an acquired distributed lock, carrying the owner token.
 * <p>
 * The ownerToken is a UUID that uniquely identifies this acquisition attempt.
 * Only the holder of this token can release the lock via Lua compare-and-delete.
 */
public record LockHandle(LockResult result, String ownerToken) {

    public static LockHandle acquired(String ownerToken) {
        return new LockHandle(LockResult.ACQUIRED, ownerToken);
    }

    public static LockHandle contended() {
        return new LockHandle(LockResult.CONTENDED, null);
    }

    public static LockHandle infraFailure() {
        return new LockHandle(LockResult.INFRA_FAILURE, null);
    }
}