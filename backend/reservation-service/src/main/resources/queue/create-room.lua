-- room 생성. scope 중복 확인과 저장을 한 스크립트에서 해, 같은 scope로 동시에 만들어도 하나만 생긴다.
-- KEYS[1] room 설정(Hash)
-- KEYS[2] 끝나지 않은 room 목록(Hash, 필드 = roomId, 값 = closeAt ms). 필드마다 closeAt에 만료된다(Redis 7.4)
-- KEYS[3..] scope 키(String, 값 = roomId). closeAt에 만료된다
-- ARGV[1] roomId, ARGV[2] scopes(쉼표로 이음), ARGV[3] openAt ms, ARGV[4] closeAt ms, ARGV[5] room 설정을 지울 시각 ms
-- 반환: 1 = 만듦, 0 = 끝나지 않은 다른 room과 scope가 겹침(아무것도 쓰지 않음)

-- scope 키는 closeAt에 만료되므로, 남아 있으면 그 scope를 쓰는 room이 아직 끝나지 않았다
for i = 3, #KEYS do
    if redis.call('EXISTS', KEYS[i]) == 1 then
        return 0
    end
end

redis.call('HSET', KEYS[1], 'scopes', ARGV[2], 'openAt', ARGV[3], 'closeAt', ARGV[4])
redis.call('PEXPIREAT', KEYS[1], ARGV[5])
for i = 3, #KEYS do
    redis.call('SET', KEYS[i], ARGV[1], 'PXAT', ARGV[4])
end
redis.call('HSET', KEYS[2], ARGV[1], ARGV[4])
redis.call('HPEXPIREAT', KEYS[2], ARGV[4], 'FIELDS', 1, ARGV[1])
return 1
