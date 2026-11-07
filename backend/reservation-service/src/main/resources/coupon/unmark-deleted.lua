-- 삭제 표시 지우기(DB 삭제가 롤백됐을 때). 정보 키가 없으면 만들지 않는다.
-- 없는 키에 HSET하면 total 없이 deleted만 있는 정보 키가 생겨, 다시 채우기(load.lua)가 "이미 있음"으로 건너뛰게 된다.
-- KEYS[1] 정보 키(Hash)
-- 반환: 1 지움, 0 정보 키 없음
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
redis.call('HSET', KEYS[1], 'deleted', '0')
return 1
