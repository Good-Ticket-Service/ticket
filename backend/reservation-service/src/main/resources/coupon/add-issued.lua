-- DB에는 있는데 Redis에 없는 회원을 추가한다(#323). 추가는 발급을 막는 쪽이라 잘못돼도 초과 발급이 생기지 않는다.
-- 정보 키가 없으면 추가하지 않는다. 정보 키 없이 회원 키만 생기면 다시 채우기(load.lua)가 지울 값만 늘어난다.
-- KEYS[1] 정보 키(Hash), KEYS[2] 회원 키(Set)
-- ARGV[1..] 회원 ID
-- 반환: 새로 추가한 회원 수, 정보 키가 없으면 -1
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -1
end
local added = 0
-- SADD 인자 수 한도를 넘지 않게 나눠 넣는다
local chunk = 1000
for i = 1, #ARGV, chunk do
    added = added + redis.call('SADD', KEYS[2], unpack(ARGV, i, math.min(i + chunk - 1, #ARGV)))
end
return added
