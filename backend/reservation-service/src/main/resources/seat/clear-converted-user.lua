-- 끝난 예약의 회원 표시 지우기(#449). 값이 아직 같을 때만 지워, 그 사이 새로 바뀐 예약의 표시를 지우지 않는다.
-- KEYS[1] 회원 키
-- ARGV[1] 지울 값("CONVERTED:예약ID")
-- 반환: 1 지움, 0 값이 달라 그대로 둠
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
return 0
