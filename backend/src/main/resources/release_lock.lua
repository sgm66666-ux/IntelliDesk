-- Safe release for distributed lock: only the owner can release.
-- KEYS[1] = lock key
-- ARGV[1] = owner token
-- Returns: 1 if released, 0 if not owner or key already gone
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
else
    return 0
end