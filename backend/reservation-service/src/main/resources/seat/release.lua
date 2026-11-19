-- Redis 선점 해제(#449). 소유자·상태 확인과 삭제를 한 번에 실행해, 예약으로 바꾸는 중인 선점을 지우지 않게 한다.
-- KEYS[1] 좌석 키, KEYS[2] 회원 키, KEYS[3] 선점 키(HASH), KEYS[4] 선점 좌석 목록(ZSET)
-- ARGV[1] holdId, ARGV[2] 회원 ID
-- 반환: {코드, 값}. 0 선점 없음, 1 다른 회원, 2 바꾸는 중, 3 이미 예약으로 바뀜(예약 ID), 4 해제함
local hold = redis.call('HMGET', KEYS[3], 'userId', 'state', 'reservationId', 'scheduleSeatId')
if not hold[1] then
    return {'0', ''}
end
if hold[1] ~= ARGV[2] then
    return {'1', ''}
end
if hold[2] == 'CONVERTING' then
    return {'2', ''}
end
if hold[2] == 'CONVERTED' then
    return {'3', hold[3]}
end

-- 좌석·회원 키는 이 선점 값일 때만 지운다. 키가 유실된 뒤 다른 선점이 쓴 값을 지우지 않기 위해서다
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
end
if redis.call('GET', KEYS[2]) == ARGV[1] then
    redis.call('DEL', KEYS[2])
end
redis.call('DEL', KEYS[3])
redis.call('ZREM', KEYS[4], hold[4])
return {'4', ''}
