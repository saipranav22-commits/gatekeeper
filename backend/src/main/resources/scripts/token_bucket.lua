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
-- [1]: allowed (1 = allowed, 0 = denied, -1 = malformed/unsafe bucket state)
-- [2]: tokens_remaining (integer floor of remaining tokens)
-- [3]: bucket_capacity (integer capacity)
-- [4]: retry_after_seconds (seconds until sufficient tokens are refilled)
-- [5]: reset_after_seconds (seconds until bucket reaches full capacity)
-- ==============================================================================

local function is_valid_positive_number(val)
    return val and type(val) == "number" and val > 0 and val == val and val < 1e14
end

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local refill_period_sec = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])
local ttl_sec = tonumber(ARGV[5])

-- 1. Validate input numerical arguments
if not is_valid_positive_number(capacity) or
   not is_valid_positive_number(refill_tokens) or
   not is_valid_positive_number(refill_period_sec) or
   not is_valid_positive_number(cost) then
    return redis.error_reply("ERR invalid numeric parameters for token bucket")
end

-- 2. Server-authoritative Redis timestamp in milliseconds
local redis_time = redis.call('TIME')
local now_sec = tonumber(redis_time[1])
local now_usec = tonumber(redis_time[2])
local now_ms = (now_sec * 1000) + math.floor(now_usec / 1000)

-- 3. Check key existence and read bucket state
local key_exists = redis.call('EXISTS', key)

local current_tokens
local last_refill_ms
local elapsed_ms = 0

if key_exists == 0 then
    -- Genuinely new consumer or naturally expired key: start full
    current_tokens = capacity
    last_refill_ms = now_ms
else
    -- Existing key: read hash fields
    local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
    local stored_tokens = data[1] and tonumber(data[1])
    local stored_last_refill_ms = data[2] and tonumber(data[2])

    -- Validate stored state:
    -- tokens must be a non-negative finite number (not nil, not NaN, >= 0, < 1e14)
    -- last_refill_ms must be a positive finite timestamp <= now_ms + 60000
    local is_tokens_valid = stored_tokens and stored_tokens == stored_tokens and stored_tokens >= 0 and stored_tokens < 1e14
    local is_refill_time_valid = stored_last_refill_ms and stored_last_refill_ms == stored_last_refill_ms and stored_last_refill_ms > 0 and stored_last_refill_ms <= (now_ms + 60000)

    if not is_tokens_valid or not is_refill_time_valid then
        -- MALFORMED EXISTING STATE: FAIL CLOSED!
        -- Return -1 in allowed to signal unsafe/malformed state to the application
        return {
            -1,
            0,
            capacity,
            0,
            0
        }
    end

    elapsed_ms = now_ms - stored_last_refill_ms
    if elapsed_ms < 0 then
        -- Clock jump / negative elapsed time: defensively clamp to 0
        elapsed_ms = 0
    end

    -- Plan change / downgrade guard: clamp existing tokens to current capacity
    current_tokens = math.min(capacity, stored_tokens)

    -- Continuous refill calculation: (elapsed_ms * refill_tokens) / (refill_period_sec * 1000)
    local tokens_to_add = (elapsed_ms * refill_tokens) / (refill_period_sec * 1000)
    current_tokens = math.min(capacity, current_tokens + tokens_to_add)
    last_refill_ms = stored_last_refill_ms
end

-- 4. Evaluate Request against Balance
local allowed = 0
if current_tokens >= cost then
    allowed = 1
    current_tokens = current_tokens - cost
    last_refill_ms = now_ms
else
    allowed = 0
    -- On rejection, do not deduct tokens. Update refill timestamp to now_ms
    last_refill_ms = now_ms
end

-- 5. Calculate Timing Metrics
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

-- Reset-After: seconds until bucket reaches full capacity
local reset_after_sec = 0
local tokens_to_full = capacity - current_tokens
if tokens_to_full > 0 and refill_tokens > 0 then
    reset_after_sec = math.ceil((tokens_to_full * refill_period_sec) / refill_tokens)
    if reset_after_sec < 0 then
        reset_after_sec = 0
    end
end

-- 6. Atomically persist updated bucket state
redis.call('HSET', key, 'tokens', tostring(current_tokens), 'last_refill_ms', tostring(last_refill_ms))
if ttl_sec and ttl_sec > 0 then
    redis.call('EXPIRE', key, ttl_sec)
end

-- 7. Return structured decision array
return {
    allowed,
    math.floor(current_tokens),
    capacity,
    retry_after_sec,
    reset_after_sec
}
