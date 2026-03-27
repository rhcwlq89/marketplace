local stockKey = KEYS[1]
local purchasedKey = KEYS[2]
local userId = ARGV[1]
local quantity = tonumber(ARGV[2])

if redis.call('SISMEMBER', purchasedKey, userId) == 1 then
    return -1
end

local remaining = redis.call('DECRBY', stockKey, quantity)
if remaining < 0 then
    redis.call('INCRBY', stockKey, quantity)
    return -2
end

redis.call('SADD', purchasedKey, userId)
return remaining
