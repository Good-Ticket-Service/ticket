-- 맞추는 작업이 DB와 비교할 Redis 상태를 한 번에 읽는다(#323).
-- KEYS[1] 정보 키(Hash), KEYS[2] 회원 키(Set)
-- 반환: 정보 키가 없으면 빈 배열, 있으면 { 총 수량, 삭제 표시(0/1), 받은 회원 수 }
local meta = redis.call('HMGET', KEYS[1], 'total', 'deleted')
if not meta[1] then
    return {}
end
local deleted = 0
if meta[2] == '1' then
    deleted = 1
end
return { tonumber(meta[1]), deleted, redis.call('SCARD', KEYS[2]) }
