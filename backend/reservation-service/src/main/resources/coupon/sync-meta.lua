-- 정보 키의 총 수량·삭제 표시를 DB 값으로 맞춘다(#323). 호출자는 쿠폰 행을 잠근 채 부른다.
-- 정보 키가 없으면 만들지 않는다. 회원 목록 없이 정보 키만 생기면 받은 회원 0명으로 판단해 초과 발급이 생긴다.
-- KEYS[1] 정보 키(Hash)
-- ARGV[1] 총 수량, ARGV[2] 삭제 표시(0/1)
-- 반환: 1 맞춤, 0 정보 키 없음
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
redis.call('HSET', KEYS[1], 'total', ARGV[1], 'deleted', ARGV[2])
return 1
