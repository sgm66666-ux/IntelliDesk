-- Rate Limit Sliding Window Log (Redis Sorted Set)
-- KEYS[1] = rate limit key (e.g. "intellidesk:dev:rl:user:42:agent")
-- ARGV[1] = window size (seconds)
-- ARGV[2] = max requests
-- ARGV[3] = unique member ID (UUID per request)

-- 1. Get current time from Redis (authoritative, avoids clock skew)
local now = redis.call("TIME")
local now_ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
local window_start = now_ms - tonumber(ARGV[1]) * 1000

-- 2. Remove expired entries
redis.call("ZREMRANGEBYSCORE", KEYS[1], 0, window_start)

-- 3. Count current entries
local count = redis.call("ZCARD", KEYS[1])

-- 4. If over limit, compute reset time from oldest entry
if count >= tonumber(ARGV[2]) then
    local oldest = redis.call("ZRANGE", KEYS[1], 0, 0, "WITHSCORES")
    local reset_ms = 0
    if #oldest >= 2 then
        reset_ms = tonumber(oldest[2]) + tonumber(ARGV[1]) * 1000
    end
    local retry_after = math.max(1, math.ceil((reset_ms - now_ms) / 1000))
    local window_seconds = tonumber(ARGV[1])
    local limit = tonumber(ARGV[2])
    return {0, 0, retry_after, math.ceil(reset_ms / 1000), limit, window_seconds}
end

-- 5. Add current request
redis.call("ZADD", KEYS[1], now_ms, ARGV[3])

-- 6. Set key TTL (window + 1 second, bounded)
redis.call("EXPIRE", KEYS[1], tonumber(ARGV[1]) + 1)

-- 7. Return allowed
local remaining = tonumber(ARGV[2]) - count - 1
local window_seconds = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
return {1, remaining, 0, math.ceil((now_ms + window_seconds * 1000) / 1000), limit, window_seconds}