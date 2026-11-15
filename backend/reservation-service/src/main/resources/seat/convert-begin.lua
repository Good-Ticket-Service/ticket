-- 결제하기 때 예약으로 바꾸기 시작(#449). 상태를 CONVERTING으로 바꿔, 바꾸는 동안 해제되지 않게 한다.
-- KEYS[1] 선점 키(HASH)
-- ARGV[1] 회원 ID, ARGV[2] 앱 시계 현재 시각 + 최소 남은 시간(epoch ms). 저장한 만료 시각이 이보다 이르면 바꾸지 않는다
-- (DB 예약이 곧바로 만료되는 것을 막는다). 키의 남은 수명(PTTL)이 아니라 저장한 만료 시각을 앱 시계로 비교한다. DB 만료 판정과 같은 기준이다
-- 반환: {코드, 값}. 0 선점 없음, 1 다른 회원, 2 이미 바뀜(예약 ID), 3 남은 시간 부족, 4 시작함,
--       5 바꾸는 중인데 남은 시간 부족(앞선 요청이 DB에 커밋했는지 앱이 DB로 확인한다)
-- Note: 이미 CONVERTING이어도 다시 시작한다. 앞선 요청이 DB에 커밋했는지 여기서는 알 수 없어, DB 트랜잭션의 "같은 회원의 기존 예약" 판정에 맡긴다
local hold = redis.call('HMGET', KEYS[1], 'userId', 'state', 'reservationId', 'expiresAt')
if not hold[1] then
    return {'0', ''}
end
if hold[1] ~= ARGV[1] then
    return {'1', ''}
end
if hold[2] == 'CONVERTED' then
    return {'2', hold[3]}
end
if tonumber(hold[4]) < tonumber(ARGV[2]) then
    if hold[2] == 'CONVERTING' then
        return {'5', ''}
    end
    return {'3', ''}
end
redis.call('HSET', KEYS[1], 'state', 'CONVERTING')
return {'4', ''}
