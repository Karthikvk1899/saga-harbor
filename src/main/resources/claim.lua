-- KEYS: outbox hash, ready list, leases zset; ARGV: now millis, lease millis
local expired = redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', ARGV[1], 'LIMIT', 0, 1)
local id
if #expired > 0 then id = expired[1] else id = redis.call('LPOP', KEYS[2]) end
if not id then return nil end
local payload = redis.call('HGET', KEYS[1], id)
if not payload then redis.call('ZREM', KEYS[3], id); return nil end
redis.call('ZADD', KEYS[3], tonumber(ARGV[1]) + tonumber(ARGV[2]), id)
return {id, payload}
