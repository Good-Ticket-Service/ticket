-- 정보 키가 없을 때만 DB 값으로 다시 채운다. 동시에 여러 요청이 채우려 해도 처음 한 번만 채워,
-- 늦게 온 요청이 그사이 기록된 회원을 옛 목록으로 덮지 못하게 한다.
-- KEYS[1] 정보 키(Hash), KEYS[2] 회원 키(Set)
-- ARGV[1] 총 수량, ARGV[2] 삭제 표시(0/1), ARGV[3..] 받은 회원 ID
-- 반환: 1 채움, 0 이미 있어 그대로 둠
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

-- 정보 키 없이 남은 회원 키는 믿을 수 없어 지우고 DB 목록으로 새로 만든다
redis.call('DEL', KEYS[2])
-- SADD 인자 수 한도를 넘지 않게 나눠 넣는다
local chunk = 1000
for i = 3, #ARGV, chunk do
    redis.call('SADD', KEYS[2], unpack(ARGV, i, math.min(i + chunk - 1, #ARGV)))
end

-- 정보 키를 마지막에 만든다. 발급 판단은 정보 키가 있을 때만 하므로 회원 목록이 다 채워진 뒤에 판단이 열린다
redis.call('HSET', KEYS[1], 'total', ARGV[1], 'deleted', ARGV[2])
return 1
