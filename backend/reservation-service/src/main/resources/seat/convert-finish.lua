-- 예약으로 바꾸기 완료(#449). DB 커밋 뒤 부른다.
-- KEYS[1] 좌석 키, KEYS[2] 회원 키, KEYS[3] 선점 키(HASH), KEYS[4] 선점 좌석 목록(ZSET), KEYS[5] 등급별 선점 좌석 목록(ZSET)
-- ARGV[1] holdId, ARGV[2] 예약 ID, ARGV[3] 회원 키 수명(ms, 상대 시간), ARGV[4] 좌석 ID
-- 반환: 0
-- 좌석 키를 지운다. 이제 DB가 좌석을 판정한다. 남겨 두면 DB에서 좌석이 풀려도(해제·만료) 키가 사라질 때까지 409가 난다.
-- 회원 키는 "CONVERTED:예약ID"로 바꿔 회차 시작까지 둔다. 이 회원이 다시 선점할 때 DB를 한 번 확인하게 하는 표시다.
-- 선점 키는 같은 요청의 재시도에 예약 ID를 돌려주려고 원래 만료까지 둔다
if redis.call('EXISTS', KEYS[3]) == 1 then
    redis.call('HSET', KEYS[3], 'state', 'CONVERTED', 'reservationId', ARGV[2])
end
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
end
redis.call('ZREM', KEYS[4], ARGV[4])
redis.call('ZREM', KEYS[5], ARGV[4])
redis.call('SET', KEYS[2], 'CONVERTED:' .. ARGV[2], 'PX', ARGV[3])
return 0
