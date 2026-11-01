-- 대기열 진입. 이미 줄에 있으면 위치만, 없으면 번호를 받아 줄 맨 뒤에 선다.
-- KEYS[1] 대기 줄(Sorted Set, score = 발급 번호)
-- KEYS[2] 번호 발급 카운터
-- KEYS[3] 대기열이 있는 공연 목록(Set). 입장 스케줄러가 이 목록을 돈다
-- KEYS[4] 마지막 조회 시각(Sorted Set, score = 시각 ms). 이탈자 정리가 이 값으로 떠난 회원을 찾는다
-- ARGV[1] 회원 ID, ARGV[2] 공연 ID
-- 반환: 내 앞에 선 인원(ZRANK, 0부터)
-- 진입도 살아 있다는 신호라 조회 시각을 남긴다. 진입만 하고 순번을 한 번도 묻지 않고 떠난 회원도 이탈 기준에 걸리게 하려는 것이다
local now = redis.call('TIME')
local nowMillis = now[1] * 1000 + math.floor(now[2] / 1000)
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if rank then
    redis.call('ZADD', KEYS[4], nowMillis, ARGV[1])
    return rank
end
local seq = redis.call('INCR', KEYS[2])
redis.call('ZADD', KEYS[1], seq, ARGV[1])
redis.call('ZADD', KEYS[4], nowMillis, ARGV[1])
redis.call('SADD', KEYS[3], ARGV[2])
return redis.call('ZRANK', KEYS[1], ARGV[1])
