-- 예약으로 바꾸기 실패(#449). DB 트랜잭션이 거절되거나 실패했을 때 부른다.
-- KEYS[1] 좌석 키, KEYS[2] 회원 키, KEYS[3] 선점 키(HASH), KEYS[4] 선점 좌석 목록(ZSET)
-- ARGV[1] holdId, ARGV[2] "drop"(좌석이 이미 DB에서 잡힘·매수 제한 등, 선점을 지움) 또는 "revert"(일시적 오류, HELD로 되돌림), ARGV[3] 좌석 ID
-- 반환: 0 처리함, 1 바꾸는 중이 아니라 그대로 둠(그 사이 다른 요청이 끝냈다)
if redis.call('HGET', KEYS[3], 'state') ~= 'CONVERTING' then
    return 1
end
if ARGV[2] == 'revert' then
    redis.call('HSET', KEYS[3], 'state', 'HELD')
    return 0
end
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
end
if redis.call('GET', KEYS[2]) == ARGV[1] then
    redis.call('DEL', KEYS[2])
end
redis.call('DEL', KEYS[3])
redis.call('ZREM', KEYS[4], ARGV[3])
return 0
