-- room 하나의 입장 처리. 입장 스케줄러가 room마다 한 번씩 부른다.
-- KEYS[1] 대기 줄(Sorted Set, score = 발급 번호)
-- KEYS[2] 마지막 조회 시각(Sorted Set, score = 시각 ms)
-- KEYS[3] 입장 목록(Sorted Set, score = 입장 만료 시각 ms)
-- KEYS[4] 마지막 입장 시각(ms). 토큰 버킷의 기준점이다
-- KEYS[5] room 설정(Hash). 입장 목록·마지막 입장 시각을 이 키와 같은 시각에 만료시킨다
-- ARGV[1] 초당 입장 인원, ARGV[2] 한 번에 쌓아 둘 수 있는 입장 시간(ms), ARGV[3] 입장 인원 상한, ARGV[4] 입장 유효 시간(ms)
-- ARGV[5] 이탈 기준(ms), ARGV[6] 한 번에 정리할 이탈자 수
-- 반환: { 입장 인원, 만료로 빠진 입장자 수, 이탈로 빠진 대기자 수, 처리 뒤 대기 인원, 처리 뒤 입장 중 인원 }
-- 줄이 비어도 키를 지우지 않는다. 줄 키는 room 설정과 같은 시각(closeAt + 입장 유효 시간)에 만료된다
-- 꺼내기와 입장 등록을 한 스크립트에서 하므로 그 사이에 장애가 나도 회원이 사라지지 않고, 서버가 여러 대여도 같은 room을 겹쳐 처리하지 않는다
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
local rate = tonumber(ARGV[1])
local staleBefore = now - tonumber(ARGV[5])

-- 1. 입장 유효 시간이 지난 입장자를 뺀다
local expired = redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)

-- 2. 조회가 이탈 기준보다 오래 끊긴 대기자를 줄에서 뺀다. 한 번에 정리할 수를 제한해 스크립트가 Redis를 오래 붙잡지 않게 한다
local stale = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', staleBefore, 'LIMIT', 0, tonumber(ARGV[6]))
if #stale > 0 then
    redis.call('ZREM', KEYS[1], unpack(stale))
    redis.call('ZREM', KEYS[2], unpack(stale))
end

-- 3. 들여보낼 인원을 정한다. 마지막 입장 시각부터 흐른 시간만큼 입장할 수 있고, 쉬는 동안 쌓이는 양은 ARGV[2]까지만 둔다.
-- Redis 시각을 기준으로 하므로 서버 시계가 달라도, 스케줄러가 늦게 돌아도 평균 속도가 유지된다
local burst = tonumber(ARGV[2])
local last = tonumber(redis.call('GET', KEYS[4]) or (now - 1000))
if last < now - burst then
    last = now - burst
end
local byRate = math.floor((now - last) * rate / 1000)
local bySeat = tonumber(ARGV[3]) - redis.call('ZCARD', KEYS[3])
local count = math.min(byRate, bySeat, redis.call('ZCARD', KEYS[1]))

-- 4. 줄 앞에서 꺼내 입장 목록에 넣는다. 입장한 회원은 대기자가 아니므로 조회 시각도 지운다.
-- 2단계는 오래 조회하지 않은 순으로 정해진 수만 정리하므로, 이탈자가 많으면 줄 앞의 이탈자가 남아 있을 수 있다.
-- 그래서 꺼낸 회원의 조회 시각을 한 번 더 보고, 이탈자면 입장시키지 않고 뺀다. 그 몫은 기준점을 옮기지 않아 다음 처리로 넘어간다
local admitted = 0
local leftAtFront = 0
if count > 0 then
    local popped = redis.call('ZPOPMIN', KEYS[1], count)
    local expireAt = now + tonumber(ARGV[4])
    for i = 1, #popped, 2 do
        local seen = redis.call('ZSCORE', KEYS[2], popped[i])
        if seen and tonumber(seen) < staleBefore then
            leftAtFront = leftAtFront + 1
        else
            redis.call('ZADD', KEYS[3], expireAt, popped[i])
            admitted = admitted + 1
        end
        redis.call('ZREM', KEYS[2], popped[i])
    end
    -- 들여보낸 인원만큼만 기준점을 옮긴다. 줄이 짧아 덜 들어간 몫은 다음 처리로 넘어간다
    last = last + admitted * 1000 / rate
end
redis.call('SET', KEYS[4], string.format('%.3f', last))

-- SET은 만료를 지우고 입장 목록은 새로 생겼을 수 있으므로, 매번 room 설정의 만료 시각을 다시 건다
local keepUntil = redis.call('PEXPIRETIME', KEYS[5])
if keepUntil > 0 then
    redis.call('PEXPIREAT', KEYS[3], keepUntil)
    redis.call('PEXPIREAT', KEYS[4], keepUntil)
end

-- 5. 대기·입장 중 인원은 지표로 내보낸다. 지표가 따로 세면 room마다 Redis 호출이 늘어나므로 여기서 함께 돌려준다
local waiting = redis.call('ZCARD', KEYS[1])
local active = redis.call('ZCARD', KEYS[3])

return { admitted, expired, #stale + leftAtFront, waiting, active }
