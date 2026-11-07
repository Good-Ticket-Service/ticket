-- 총 수량 변경. 받은 회원 수와 비교와 변경을 한 번에 실행해, 비교한 뒤 발급이 끼어들어 받은 수보다 줄어드는 일을 막는다.
-- KEYS[1] 정보 키(Hash), KEYS[2] 회원 키(Set)
-- ARGV[1] 새 총 수량
-- 반환: 0 바꿈, 1 정보 키 없음, 2 받은 회원 수보다 적음
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 1
end
if redis.call('SCARD', KEYS[2]) > tonumber(ARGV[1]) then
    return 2
end
redis.call('HSET', KEYS[1], 'total', ARGV[1])
return 0
