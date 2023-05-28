--获得传入的key
local key = KEYS[1]
--获得传入的value
local value = ARGV[1]
--执行redis命令   get key
local threadId = redis.call('get',key)
if (value == threadId) then
    --是自己的线程  执行del dey
   return redis.call('del',key)
end
return 0