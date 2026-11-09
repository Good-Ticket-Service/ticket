-- 두 키에 남겨 둘 시간을 다시 건다(#323). 관리자가 종료 시각을 바꾸거나, 첫 발급이 유효 시간 없이 회원 키를 만들었을 때 맞춘다.
-- KEYS[1] 정보 키(Hash), KEYS[2] 회원 키(Set)
-- ARGV[1] 키를 남겨 둘 시간(초)
-- 반환: 유효 시간을 건 키 수. 없는 키는 건너뛴다
return redis.call('EXPIRE', KEYS[1], ARGV[1]) + redis.call('EXPIRE', KEYS[2], ARGV[1])
