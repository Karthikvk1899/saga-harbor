-- KEYS: saga hash, outbox hash, ready list
-- ARGV: result command id, success 1/0, next command id, outbox id, JSON command, capacity
local state = redis.call('HGET', KEYS[1], 'state')
if not state then return {-1, 'unknown_saga'} end
if redis.call('HGET', KEYS[1], 'commandId') ~= ARGV[1] then return {0, state} end
local nextState = ''
local nextAction = false
if state == 'RESERVING' then
  if ARGV[2] == '1' then nextState = 'CHARGING'; nextAction = true else nextState = 'FAILED' end
elseif state == 'CHARGING' then
  if ARGV[2] == '1' then nextState = 'COMPLETED' else nextState = 'RELEASING'; nextAction = true end
elseif state == 'RELEASING' then
  if ARGV[2] == '1' then nextState = 'COMPENSATED' else nextState = 'MANUAL_REVIEW' end
else return {0, state} end
if nextAction and redis.call('HLEN', KEYS[2]) >= tonumber(ARGV[6]) then return {-2, 'outbox_full'} end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
redis.call('HINCRBY', KEYS[1], 'version', 1)
redis.call('HSET', KEYS[1], 'state', nextState, 'commandId', nextAction and ARGV[3] or '', 'updatedAtMs', now)
if nextAction then
  redis.call('HSET', KEYS[2], ARGV[4], ARGV[5])
  redis.call('RPUSH', KEYS[3], ARGV[4])
end
return {1, nextState}
