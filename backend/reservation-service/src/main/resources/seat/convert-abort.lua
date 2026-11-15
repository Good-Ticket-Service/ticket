-- 예약으로 바꾸기 실패(#449). DB 트랜잭션이 거절되거나 실패했을 때 부른다.
-- KEYS[1] 좌석 키, KEYS[2] 회원 키, KEYS[3] 선점 키(HASH), KEYS[4] 선점 좌석 목록(ZSET), KEYS[5] 등급별 선점 좌석 목록(ZSET)
-- ARGV[1] holdId, ARGV[2] "drop"(좌석이 이미 DB에서 잡힘·매수 제한 등, 선점을 지움) 또는 "revert"(일시적 오류, HELD로 되돌림), ARGV[3] 좌석 ID
-- 반환: 0 처리함, 1 바꾸는 중이 아니라 그대로 둠(그 사이 다른 요청이 끝냈다)
if redis.call('HGET', KEYS[3], 'state') ~= 'CONVERTING' then
    return 1
end
if ARGV[2] == 'revert' then
    redis.call('HSET', KEYS[3], 'state', 'HELD')
    return 0
end
-- 좌석 목록에서는 좌석 키가 다른 선점의 것이 아닐 때만 뺀다. 이 선점이 끝난 뒤 다른 회원이 같은 좌석을 잡았으면 그 회원의 항목이다
local seatOwner = redis.call('GET', KEYS[1])
local ownsListEntry = (not seatOwner) or seatOwner == ARGV[1]
if seatOwner == ARGV[1] then
    redis.call('DEL', KEYS[1])
end
if redis.call('GET', KEYS[2]) == ARGV[1] then
    redis.call('DEL', KEYS[2])
end
redis.call('DEL', KEYS[3])
if ownsListEntry then
    redis.call('ZREM', KEYS[4], ARGV[3])
    redis.call('ZREM', KEYS[5], ARGV[3])
end
return 0
