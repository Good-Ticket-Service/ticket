-- 삭제 표시. 받은 회원이 없을 때만 남긴다. 발급 판단(issue.lua)과 같은 키를 보므로 삭제와 첫 발급 중 하나만 성공한다.
-- KEYS[1] 정보 키(Hash), KEYS[2] 회원 키(Set)
-- 반환: 0 표시함, 1 정보 키 없음, 2 받은 회원 있음
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 1
end
if redis.call('SCARD', KEYS[2]) > 0 then
    return 2
end
redis.call('HSET', KEYS[1], 'deleted', '1')
return 0
