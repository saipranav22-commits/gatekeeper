-- ==============================================================================
-- Gatekeeper Distributed Token Bucket Rate Limiter (Atomic Redis Lua Script)
-- ==============================================================================
-- KEYS[1]: Bucket key (e.g. gatekeeper:ratelimit:consumer:{consumerId})
-- ARGV[1]: capacity (number, maximum tokens the bucket can hold)
-- ARGV[2]: refill_tokens (number, tokens replenished per refill period)
-- ARGV[3]: refill_period_sec (number, period in seconds for refill_tokens)
-- ARGV[4]: cost (number, tokens requested for this operation, >= 1)
-- ARGV[5]: ttl_sec (number, TTL in seconds to set on the bucket key)
--
-- Return array:
-- [1]: allowed (1 = allowed, 0 = denied)
-- [2]: tokens_remaining (integer floor of remaining tokens)
-- [3]: bucket_capacity (integer capacity)
-- [4]: retry_after_seconds (seconds until sufficient tokens are refilled)
-- [5]: reset_after_seconds (seconds until bucket reaches full capacity)
-- ==============================================================================

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local refill_period_sec = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])
local ttl_sec = tonumber(ARGV[5])

-- 1. Server-authoritative Redis timestamp in milliseconds
local redis_time = redis.call('TIME')
local now_sec = tonumber(redis_time[1])
local now_usec = tonumber(redis_time[2])
local now_ms = (now_sec * 1000) + math.floor(now_usec / 1000)

-- 2. Read existing bucket state
local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
local stored_tokens = data[1] and tonumber(data[1])
local last_refill_ms = data[2] and tonumber(data[2])

local current_tokens
local elapsed_ms

if not stored_tokens or not last_refill_ms then
    -- Initial bucket creation or expired key: start full
    current_tokens = capacity
    elapsed_ms = 0
    last_refill_ms = now_ms
else
    -- Existing bucket: defensive calculation of elapsed time
    elapsed_ms = now_ms - last_refill_ms
    if elapsed_ms < 0 then
        -- Clock jump / negative elapsed time: treat as 0 to avoid corruption
        elapsed_ms = 0
    end

    -- Plan change / downgrade guard: clamp existing tokens to current capacity
    current_tokens = math.min(capacity, stored_tokens)

    -- Continuous refill calculation: (elapsed_ms * refill_tokens) / (refill_period_sec * 1000)
    local tokens_to_add = (elapsed_ms * refill_tokens) / (refill_period_sec * 1000)
    current_tokens = math.min(capacity, current_tokens + tokens_to_add)
end

-- 3. Evaluate Request against Balance
local allowed = 0
if current_tokens >= cost then
    allowed = 1
    current_tokens = current_tokens - cost
    -- Update last_refill_ms to account for elapsed time refilled up to this moment
    last_refill_ms = now_ms
else
    allowed = 0
    -- On rejection, do NOT consume tokens.
    -- Still update last_refill_ms to now_ms because refilled tokens were credited up to now_ms
    last_refill_ms = now_ms
end

-- 4. Calculate Timing Metrics
-- Retry-After: seconds until enough tokens have refilled to satisfy 'cost'
local retry_after_sec = 0
if allowed == 0 then
    local tokens_needed = cost - current_tokens
    if tokens_needed > 0 and refill_tokens > 0 then
        retry_after_sec = math.ceil((tokens_needed * refill_period_sec) / refill_tokens)
        if retry_after_sec < 1 then
            retry_after_sec = 1
        end
    else
        retry_after_sec = 1
    end
end

-- Reset-After: seconds until bucket is completely full at capacity
local reset_after_sec = 0
local tokens_to_full = capacity - current_tokens
if tokens_to_full > 0 and refill_tokens > 0 then
    reset_after_sec = math.ceil((tokens_to_full * refill_period_sec) / refill_tokens)
    if reset_after_sec < 0 then
        reset_after_sec = 0
    end
end

-- 5. Atomically persist updated bucket state
redis.call('HSET', key, 'tokens', tostring(current_tokens), 'last_refill_ms', tostring(last_refill_ms))
if ttl_sec and ttl_sec > 0 then
    redis.call('EXPIRE', key, ttl_sec)
end

-- 6. Return structured decision array
return {
    allowed,
    math.floor(current_tokens),
    capacity,
    retry_after_sec,
    reset_after_sec
}
