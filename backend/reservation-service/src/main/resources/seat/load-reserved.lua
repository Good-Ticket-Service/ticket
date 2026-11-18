-- DB 점유 좌석을 Redis에 올린다. 회차 하나를 여러 묶음으로 나눠 부르고, 마지막 묶음에서 올림 표시를 남긴다.
-- KEYS[1] DB 점유 좌석(HASH, 좌석 ID = 예약 ID), KEYS[2] 올림 표시(STRING)
-- ARGV[1] 키 수명(ms), ARGV[2] "1"이면 마지막 묶음(올림 표시를 남김), ARGV[3..] 좌석 ID·예약 ID 쌍
-- 반환: 0
-- Note: HSETNX로 이미 있는 값을 덮지 않는다. DB를 읽은 뒤 결제하기가 끝나 더 새 예약을 넣었을 수 있기 때문이다
for i = 3, #ARGV, 2 do
    redis.call('HSETNX', KEYS[1], ARGV[i], ARGV[i + 1])
end
if redis.call('PTTL', KEYS[1]) < tonumber(ARGV[1]) then
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
if ARGV[2] == '1' then
    redis.call('SET', KEYS[2], '1', 'PX', ARGV[1])
end
return 0
