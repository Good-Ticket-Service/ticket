-- 순번 조회. 줄에 있으면 마지막 조회 시각을 남기고 위치를, 입장해 있으면 입장 만료 시각을 돌려준다.
-- KEYS[1] 대기 줄(Sorted Set, score = 발급 번호)
-- KEYS[2] 마지막 조회 시각(Sorted Set, score = 조회 시각 ms). 이탈자 정리가 이 값으로 떠난 회원을 찾는다
-- KEYS[3] 입장 목록(Sorted Set, score = 입장 만료 시각 ms)
-- ARGV[1] 회원 ID
-- 반환: { 0, 내 앞에 선 인원(ZRANK, 0부터) }, 입장해 있으면 { 1, 입장 만료 시각 ms }, 둘 다 아니면 { -1, 0 }
-- 서버마다 시계가 달라도 이탈·만료 판단이 어긋나지 않게 Redis 시계를 쓴다
local now = redis.call('TIME')
local nowMillis = now[1] * 1000 + math.floor(now[2] / 1000)
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if rank then
    redis.call('ZADD', KEYS[2], nowMillis, ARGV[1])
    return { 0, rank }
end
-- 입장한 회원은 대기자가 아니므로 조회 시각을 남기지 않는다. 만료가 지났으면 스케줄러가 아직 정리하지 않았어도 줄에 없는 회원으로 본다
local expireAt = redis.call('ZSCORE', KEYS[3], ARGV[1])
if expireAt and tonumber(expireAt) > nowMillis then
    return { 1, tonumber(expireAt) }
end
return { -1, 0 }
