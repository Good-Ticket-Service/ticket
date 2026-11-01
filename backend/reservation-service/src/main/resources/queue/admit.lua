-- 공연 하나의 입장 처리. 입장 스케줄러가 공연마다 한 번씩 부른다.
-- KEYS[1] 대기 줄(Sorted Set, score = 발급 번호)
-- KEYS[2] 마지막 조회 시각(Sorted Set, score = 시각 ms)
-- KEYS[3] 입장 목록(Sorted Set, score = 입장 만료 시각 ms)
-- KEYS[4] 마지막 입장 시각(ms). 토큰 버킷의 기준점이다
-- ARGV[1] 초당 입장 인원, ARGV[2] 한 번에 쌓아 둘 수 있는 입장 시간(ms), ARGV[3] 입장 인원 상한, ARGV[4] 입장 유효 시간(ms)
-- 반환: { 입장 인원, 만료로 빠진 입장자 수 }
-- 꺼내기와 입장 등록을 한 스크립트에서 하므로 그 사이에 장애가 나도 회원이 사라지지 않고, 서버가 여러 대여도 같은 공연을 겹쳐 처리하지 않는다
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
local rate = tonumber(ARGV[1])

-- 1. 입장 유효 시간이 지난 입장자를 뺀다
local expired = redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)

-- 2. 들여보낼 인원을 정한다. 마지막 입장 시각부터 흐른 시간만큼 입장할 수 있고, 쉬는 동안 쌓이는 양은 ARGV[2]까지만 둔다.
-- Redis 시각을 기준으로 하므로 서버 시계가 달라도, 스케줄러가 늦게 돌아도 평균 속도가 유지된다
local burst = tonumber(ARGV[2])
local last = tonumber(redis.call('GET', KEYS[4]) or (now - 1000))
if last < now - burst then
    last = now - burst
end
local byRate = math.floor((now - last) * rate / 1000)
local bySeat = tonumber(ARGV[3]) - redis.call('ZCARD', KEYS[3])
local count = math.min(byRate, bySeat, redis.call('ZCARD', KEYS[1]))

-- 3. 줄 앞에서 꺼내 입장 목록에 넣는다. 입장한 회원은 대기자가 아니므로 조회 시각도 지운다
local admitted = 0
if count > 0 then
    local popped = redis.call('ZPOPMIN', KEYS[1], count)
    local expireAt = now + tonumber(ARGV[4])
    for i = 1, #popped, 2 do
        redis.call('ZADD', KEYS[3], expireAt, popped[i])
        redis.call('ZREM', KEYS[2], popped[i])
        admitted = admitted + 1
    end
    -- 들여보낸 인원만큼만 기준점을 옮긴다. 줄이 짧아 덜 들어간 몫은 다음 처리로 넘어간다
    last = last + admitted * 1000 / rate
end
redis.call('SET', KEYS[4], string.format('%.3f', last))

return { admitted, expired }
