-- 총 수량 되돌리기(DB 저장이 롤백됐을 때). 아직 바꾼 값 그대로일 때만 되돌려, 그사이 다른 변경을 덮지 않는다.
-- KEYS[1] 정보 키(Hash)
-- ARGV[1] 지금 있어야 할 값(바꾼 값), ARGV[2] 되돌릴 값
-- 반환: 1 되돌림, 0 그대로 둠
if redis.call('HGET', KEYS[1], 'total') == ARGV[1] then
    redis.call('HSET', KEYS[1], 'total', ARGV[2])
    return 1
end
return 0
