-- 예약으로 바꾸기 완료(#449). DB 커밋 뒤 부른다.
-- KEYS[1] 좌석 키, KEYS[2] 회원 키, KEYS[3] 선점 키(HASH), KEYS[4] 선점 좌석 목록(ZSET), KEYS[5] 등급별 선점 좌석 목록(ZSET),
-- KEYS[6] DB 점유 좌석(HASH), KEYS[7] 점유 좌석을 다 올렸다는 표시
-- ARGV[1] holdId, ARGV[2] 예약 ID, ARGV[3] 회원 키 수명(ms, 상대 시간), ARGV[4] 좌석 ID
-- 반환: 0
-- 좌석 키를 지운다. 남겨 두면 DB에서 좌석이 풀려도(해제·만료) 키가 사라질 때까지 409가 난다.
-- 점유 좌석을 올린 회차면 KEYS[6]에 이 예약을 넣는다. 다음 선점이 DB를 읽지 않고 잡힌 좌석인지 안다. DB가 좌석을 풀 때 지운다.
-- 올리지 않은 회차에는 넣지 않는다. 그 사이 DB에서 풀린 좌석을 지워 줄 곳이 없어, 나중에 올릴 때 낡은 값이 남기 때문이다
-- 회원 키는 "CONVERTED:예약ID"로 바꿔 회차 시작까지 둔다. 이 회원이 다시 선점할 때 DB를 한 번 확인하게 하는 표시다.
-- 선점 키는 같은 요청의 재시도에 예약 ID를 돌려주려고 원래 만료까지 둔다
if redis.call('EXISTS', KEYS[3]) == 1 then
    redis.call('HSET', KEYS[3], 'state', 'CONVERTED', 'reservationId', ARGV[2])
end
-- 좌석 목록에서는 좌석 키가 다른 선점의 것이 아닐 때만 뺀다. 이 선점이 끝난 뒤 다른 회원이 같은 좌석을 잡았으면 그 회원의 항목이다
local seatOwner = redis.call('GET', KEYS[1])
local ownsListEntry = (not seatOwner) or seatOwner == ARGV[1]
if seatOwner == ARGV[1] then
    redis.call('DEL', KEYS[1])
end
if ownsListEntry then
    redis.call('ZREM', KEYS[4], ARGV[4])
    redis.call('ZREM', KEYS[5], ARGV[4])
end
redis.call('SET', KEYS[2], 'CONVERTED:' .. ARGV[2], 'PX', ARGV[3])
if redis.call('EXISTS', KEYS[7]) == 1 then
    redis.call('HSET', KEYS[6], ARGV[4], ARGV[2])
    if redis.call('PTTL', KEYS[6]) < tonumber(ARGV[3]) then
        redis.call('PEXPIRE', KEYS[6], ARGV[3])
    end
end
return 0
