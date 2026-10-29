-- 순번 조회. 줄에 있으면 마지막 조회 시각을 남기고 위치를 돌려준다.
-- KEYS[1] 대기 줄(Sorted Set, score = 발급 번호)
-- KEYS[2] 마지막 조회 시각(Sorted Set, score = 조회 시각 ms). 이탈자 정리가 이 값으로 떠난 회원을 찾는다
-- ARGV[1] 회원 ID
-- 반환: 내 앞에 선 인원(ZRANK, 0부터). 줄에 없으면 -1
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if not rank then
    return -1
end
-- 서버마다 시계가 달라도 이탈 판단이 어긋나지 않게 Redis 시계를 쓴다
local now = redis.call('TIME')
redis.call('ZADD', KEYS[2], now[1] * 1000 + math.floor(now[2] / 1000), ARGV[1])
return rank
