-- 대기열 진입. 이미 줄에 있으면 위치만, 없으면 번호를 받아 줄 맨 뒤에 선다.
-- KEYS[1] 대기 줄(Sorted Set, score = 발급 번호)
-- KEYS[2] 번호 발급 카운터
-- KEYS[3] 대기열이 있는 공연 목록(Set). 입장 스케줄러가 이 목록을 돈다
-- ARGV[1] 회원 ID, ARGV[2] 공연 ID
-- 반환: 내 앞에 선 인원(ZRANK, 0부터)
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if rank then
    return rank
end
local seq = redis.call('INCR', KEYS[2])
redis.call('ZADD', KEYS[1], seq, ARGV[1])
redis.call('SADD', KEYS[3], ARGV[2])
return redis.call('ZRANK', KEYS[1], ARGV[1])
