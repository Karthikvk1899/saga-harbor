-- KEYS: saga hash, idempotency mapping, outbox hash, ready list
-- ARGV: saga id, order id, command id, event id, serialized command, capacity
local existing = redis.call('GET', KEYS[2])
if existing then return {0, existing} end
if redis.call('HLEN', KEYS[3]) >= tonumber(ARGV[6]) then return {-1, 'outbox_full'} end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
redis.call('HSET', KEYS[1], 'id', ARGV[1], 'orderId', ARGV[2], 'state', 'RESERVING', 'commandId', ARGV[3], 'version', 1, 'createdAtMs', now, 'updatedAtMs', now)
redis.call('SET', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[3], ARGV[4], ARGV[5])
redis.call('RPUSH', KEYS[4], ARGV[4])
return {1, ARGV[1]}
