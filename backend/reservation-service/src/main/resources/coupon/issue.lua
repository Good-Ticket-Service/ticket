-- 쿠폰 발급 판단. 확인과 기록을 한 번에 실행해, 남은 한 자리를 두 요청이 함께 통과하지 못하게 한다.
-- KEYS[1] 정보 키(Hash: total = 총 수량, deleted = 삭제 표시 0/1)
-- KEYS[2] 회원 키(Set: 받은 회원 ID). 회원 수가 곧 발급 수량이다
-- ARGV[1] 회원 ID, ARGV[2] 발급 기간 안이면 1(자바가 DB에서 읽은 기간으로 계산)
-- 반환: 0 기록함, 1 정보 키 없음, 2 이미 받음, 3 삭제됨, 4 기간 밖, 5 소진
local meta = redis.call('HMGET', KEYS[1], 'total', 'deleted')

-- 정보가 없으면 판단할 수 없다. 자바가 DB에서 채운 뒤 다시 부른다
if not meta[1] then
    return 1
end

-- 오류 우선순위: 이미 받음 → 삭제 → 기간 → 소진 (CouponIssueService의 지금 순서와 같다)
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end
if meta[2] == '1' then
    return 3
end
if ARGV[2] ~= '1' then
    return 4
end
if redis.call('SCARD', KEYS[2]) >= tonumber(meta[1]) then
    return 5
end

redis.call('SADD', KEYS[2], ARGV[1])
return 0
