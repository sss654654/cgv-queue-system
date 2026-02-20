-- seat_lock.lua
-- All-or-nothing multi-seat lock using SET NX EX
--
-- KEYS: seat:{movieId}:{theaterId}:{seatId} for each seat (1..N)
-- ARGV[1]: requestId (lock owner value)
-- ARGV[2]: TTL in seconds (300 = 5 minutes)
--
-- Returns:
--   {1}                          on success (all seats locked)
--   {0, conflict1, conflict2..}  on conflict (no seats locked)

local conflicts = {}

-- Phase 1: Check all seats are available
for i, key in ipairs(KEYS) do
    if redis.call('EXISTS', key) == 1 then
        table.insert(conflicts, key)
    end
end

-- If any seat is already locked, return conflicts without locking anything
if #conflicts > 0 then
    return {0, unpack(conflicts)}
end

-- Phase 2: All seats available - lock them atomically
for i, key in ipairs(KEYS) do
    redis.call('SET', key, ARGV[1], 'NX', 'EX', tonumber(ARGV[2]))
end

return {1}
