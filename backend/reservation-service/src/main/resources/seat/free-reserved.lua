-- DB에서 풀린 좌석을 Redis 점유 좌석에서 지운다(#459). 값이 그 예약일 때만 지운다.
-- 그 사이 결제하기가 같은 좌석에 새 예약을 넣었으면 그 값은 남긴다.
-- KEYS[1] DB 점유 좌석(HASH, 좌석 ID = 예약 ID)
-- ARGV 좌석 ID·예약 ID 쌍
-- 반환: 지운 좌석 수
local removed = 0
for i = 1, #ARGV, 2 do
    if redis.call('HGET', KEYS[1], ARGV[i]) == ARGV[i + 1] then
        redis.call('HDEL', KEYS[1], ARGV[i])
        removed = removed + 1
    end
end
return removed
