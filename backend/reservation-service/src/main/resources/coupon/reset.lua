-- 새 쿠폰의 발급 판단 정보를 만든다. 같은 ID로 남은 이전 값을 지우고 받은 회원 0명으로 시작한다.
-- 로컬은 ddl-auto: create라 재시작하면 쿠폰 ID가 1부터 다시 쓰이는데, Redis에는 이전 쿠폰의 회원이 남아 있을 수 있다.
-- KEYS[1] 정보 키(Hash), KEYS[2] 회원 키(Set)
-- ARGV[1] 총 수량, ARGV[2] 키를 남겨 둘 시간(초, 지금부터 발급 종료 + 1일까지)
-- Note: 회원 키는 첫 발급(issue.lua)이 만들므로 여기서 유효 시간을 걸 수 없다. 맞추는 작업이 주기마다 두 키에 다시 건다(#323)
redis.call('DEL', KEYS[2])
redis.call('HSET', KEYS[1], 'total', ARGV[1], 'deleted', '0')
redis.call('EXPIRE', KEYS[1], ARGV[2])
return 1
