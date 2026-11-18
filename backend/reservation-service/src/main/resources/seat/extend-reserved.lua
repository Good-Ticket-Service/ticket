-- 올린 DB 점유 좌석과 올림 표시의 수명을 늘린다(#476). 판매 중인 회차가 하루 수명으로 사라져 다시 올리지 않게 한다.
-- 올림 표시가 이미 사라졌으면 아무것도 하지 않는다. 표시 없이 수명만 늘리면 덜 올린 값을 다 올린 것으로 볼 수 있다.
-- KEYS[1] DB 점유 좌석(HASH), KEYS[2] 올림 표시(STRING)
-- ARGV[1] 키 수명(ms)
-- 반환: 1 늘림, 0 표시 없음
if redis.call('EXISTS', KEYS[2]) == 0 then
    return 0
end
redis.call('PEXPIRE', KEYS[2], ARGV[1])
if redis.call('PTTL', KEYS[1]) < tonumber(ARGV[1]) then
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return 1
