-- 대기열 진입. 입장해 있으면 입장 상태를, 이미 줄에 있으면 위치만, 없으면 번호를 받아 줄 맨 뒤에 선다.
-- KEYS[1] 대기 줄(Sorted Set, score = 발급 번호)
-- KEYS[2] 번호 발급 카운터
-- KEYS[3] 마지막 조회 시각(Sorted Set, score = 시각 ms). 이탈자 정리가 이 값으로 떠난 회원을 찾는다
-- KEYS[4] 입장 목록(Sorted Set, score = 입장 만료 시각 ms)
-- KEYS[5] room 설정(Hash). 줄 키를 이 키와 같은 시각에 만료시킨다
-- ARGV[1] 회원 ID
-- 반환: { 0, 내 앞에 선 인원(ZRANK, 0부터) } 또는 입장해 있으면 { 1, 입장 만료 시각 ms }
-- 진입도 살아 있다는 신호라 조회 시각을 남긴다. 진입만 하고 순번을 한 번도 묻지 않고 떠난 회원도 이탈 기준에 걸리게 하려는 것이다
local now = redis.call('TIME')
local nowMillis = now[1] * 1000 + math.floor(now[2] / 1000)

-- 입장한 회원을 다시 줄에 세우면 입장을 두 번 받는다. 만료가 지났는데 아직 정리되지 않았으면 입장 목록에서 빼고 새로 줄을 선다
local expireAt = redis.call('ZSCORE', KEYS[4], ARGV[1])
if expireAt then
	if tonumber(expireAt) > nowMillis then
		return { 1, tonumber(expireAt) }
	end
	redis.call('ZREM', KEYS[4], ARGV[1])
end

local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if rank then
	redis.call('ZADD', KEYS[3], nowMillis, ARGV[1])
	return { 0, rank }
end

local seq = redis.call('INCR', KEYS[2])
redis.call('ZADD', KEYS[1], seq, ARGV[1])
redis.call('ZADD', KEYS[3], nowMillis, ARGV[1])
-- 새로 생겼을 수 있는 키(줄·번호·조회 시각)에 room 설정의 만료 시각을 그대로 건다(room이 끝나면 함께 지워진다).
-- 이미 서 있는 회원 경로는 만료를 걸지 않는다. 줄에 회원이 있으면 조회 시각 키도 있으므로 ZADD가 만료를 지우지 않는다
local keepUntil = redis.call('PEXPIRETIME', KEYS[5])
if keepUntil > 0 then
	redis.call('PEXPIREAT', KEYS[1], keepUntil)
	redis.call('PEXPIREAT', KEYS[2], keepUntil)
	redis.call('PEXPIREAT', KEYS[3], keepUntil)
end
return { 0, redis.call('ZRANK', KEYS[1], ARGV[1]) }
