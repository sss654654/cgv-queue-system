-- booking_complete.lua
-- Atomic booking completion: ZREM active + SADD booked + INCR counter + sold-out detection
--
-- KEYS[1]: sessions:{movieId}:active       (Sorted Set - active sessions)
-- KEYS[2]: booked:{movieId}:{theaterId}    (Set - booked seats per theater)
-- KEYS[3]: booking:completed:{movieId}     (String - completed booking counter)
-- KEYS[4]: sold-out:{movieId}              (String - sold-out flag)
--
-- ARGV[1]: member (requestId - to remove from active session)
-- ARGV[2]: seatIds (comma-separated, e.g. "A1,A2,A3")
-- ARGV[3]: totalSeats (6000 = 20 theaters x 300 seats)
--
-- Returns:
--   {0, 'ALREADY_COMPLETED'}                 if requestId not in active set (idempotent)
--   {1, 'COMPLETED', completedCount, 0}      on success (not sold out)
--   {1, 'COMPLETED', completedCount, 1}      on success (sold out triggered)

-- Step 1: Remove from active session (idempotency check)
local removed = redis.call('ZREM', KEYS[1], ARGV[1])
if removed == 0 then
    return {0, 'ALREADY_COMPLETED'}
end

-- Step 2: Add seats to booked set for this theater
for seat in string.gmatch(ARGV[2], '([^,]+)') do
    redis.call('SADD', KEYS[2], seat)
end

-- Step 3: Increment global completed counter
local count = redis.call('INCR', KEYS[3])

-- Step 4: Check if all seats are sold out
local totalSeats = tonumber(ARGV[3])
local isSoldOut = 0
if count >= totalSeats then
    redis.call('SET', KEYS[4], '1', 'EX', 3600)
    isSoldOut = 1
end

return {1, 'COMPLETED', count, isSoldOut}
