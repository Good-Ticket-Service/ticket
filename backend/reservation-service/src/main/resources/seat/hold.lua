-- Redis 좌석 선점(#449). 확인과 기록을 한 번에 실행해, 같은 좌석·같은 회원의 동시 선점 중 하나만 기록되게 한다.
-- KEYS[1] 좌석 키(STRING = holdId), KEYS[2] 회원 키(STRING = holdId 또는 "CONVERTED:예약ID"), KEYS[3] 선점 키(HASH), KEYS[4] 선점 좌석 목록(ZSET),
-- KEYS[5] 등급별 선점 좌석 목록(ZSET, 잔여석에서 뺀다), KEYS[6] DB 점유 좌석(HASH, 좌석 ID = 예약 ID), KEYS[7] 점유 좌석을 다 올렸다는 표시
-- ARGV[1] holdId, ARGV[2] 좌석 ID, ARGV[3] 만료 시각(앱 시계 epoch ms, 목록 점수), ARGV[4] 키 수명(ms), ARGV[5] 앱 시계 현재 시각(epoch ms),
-- ARGV[6] DB에서 이 좌석을 만료 전 HOLD로 잡고 있는 예약 ID(없으면 빈 문자열, 앱이 잠금 없이 읽은 값),
-- ARGV[7] "1"이면 ARGV[6] 대신 KEYS[6]으로 점유를 판정한다(DB 좌석을 읽지 않음). KEYS[7]이 없으면 기록하지 않고 6을 돌려준다
-- ARGV[8..] 선점 HASH의 필드·값 쌍
-- Note: 키 만료는 상대 시간(PX)으로 쓴다. 절대 시각(PXAT)은 Redis 시계로 해석되어, 두 시계가 다르면 키 수명이 앱 의도와 달라진다
-- 반환: {코드, 값}. 0 기록함(holdId), 1 같은 회원·같은 좌석(기존 holdId), 2 예약으로 바꾼 회원(예약 ID), 3 좌석을 다른 선점이 잡음, 4 회원이 다른 좌석을 잡음,
--       5 DB에서 이 좌석을 잡은 예약이 이 회원이 바꾼 예약임(예약 ID), 6 점유 좌석을 아직 올리지 않음(ARGV[7]이 "1"일 때만)
-- 판정 순서는 DB 방식과 같게 "내 선점 → 좌석 점유 → 매수 제한"이다
local user = redis.call('GET', KEYS[2])
local seat = redis.call('GET', KEYS[1])

if user and seat and user == seat then
    return {'1', user}
end
-- DB 점유. 앱이 읽은 값(ARGV[6]) 또는 Redis에 올린 점유 좌석(KEYS[6])이다.
-- Note: KEYS[6]에는 만료 시각이 지났지만 아직 정리 전인 HOLD와 SOLD도 들어 있다. DB가 좌석을 풀 때 지운다(만료 스케줄러 간격만큼 늦다)
local reservedBy = ARGV[6]
if ARGV[7] == '1' then
    if redis.call('EXISTS', KEYS[7]) == 0 then
        return {'6', ''}
    end
    reservedBy = redis.call('HGET', KEYS[6], ARGV[2]) or ''
end
-- 예약으로 바뀐 좌석은 Redis 좌석 키가 없고 DB가 잡고 있다. 이 회원이 바꾼 예약이면 내 선점, 아니면 남의 선점이다
if reservedBy ~= '' then
    if user == 'CONVERTED:' .. reservedBy then
        return {'5', reservedBy}
    end
    return {'3', ''}
end
-- 예약으로 바꾼 회원은 그 예약이 아직 살아 있는지 DB로 확인해야 한다. 같은 좌석이면 기존 예약을 돌려줄 수도 있어 좌석 점유보다 먼저 본다
if user and string.sub(user, 1, 10) == 'CONVERTED:' then
    return {'2', string.sub(user, 11)}
end
if seat then
    return {'3', ''}
end
if user then
    return {'4', ''}
end

local ttl = ARGV[4]
redis.call('SET', KEYS[1], ARGV[1], 'PX', ttl)
redis.call('SET', KEYS[2], ARGV[1], 'PX', ttl)
redis.call('HSET', KEYS[3], unpack(ARGV, 8))
redis.call('PEXPIRE', KEYS[3], ttl)

-- 좌석 화면(전체 목록)과 잔여석(등급별 목록)에 쓰는 목록. 점수(만료 시각)와 같은 앱 시계로 만료된 좌석을 치우고, 목록 키는 가장 늦은 만료보다 1분 뒤에 사라지게 한다
local nowMs = tonumber(ARGV[5])
for i = 4, 5 do
    redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', nowMs)
    redis.call('ZADD', KEYS[i], ARGV[3], ARGV[2])
    local last = redis.call('ZRANGE', KEYS[i], -1, -1, 'WITHSCORES')
    redis.call('PEXPIRE', KEYS[i], math.max(1, tonumber(last[2]) - nowMs + 60000))
end
return {'0', ARGV[1]}
