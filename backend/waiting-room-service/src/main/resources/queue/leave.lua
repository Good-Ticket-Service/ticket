-- 대기 취소. 대기 줄과 마지막 조회 시각에서 함께 지운다. 줄에 없어도 아무 일 없이 끝난다.
-- KEYS[1] 대기 줄(Sorted Set, score = 발급 번호)
-- KEYS[2] 마지막 조회 시각(Sorted Set, score = 조회 시각 ms)
-- ARGV[1] 회원 ID
-- 반환: 대기 줄에서 지운 수(0 또는 1)
local removed = redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
return removed
