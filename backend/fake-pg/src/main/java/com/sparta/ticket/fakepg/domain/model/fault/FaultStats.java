package com.sparta.ticket.fakepg.domain.model.fault;

/**
 * PG API 하나(제공자 × 동작)에서 장애가 실제로 걸린 횟수. 명목 확률과 실제 적용 건수를 비교할 때 쓴다.
 *
 * @param requests 들어온 요청 수. 처리 한도 초과·멱등 키 재현도 포함한다
 * @param latencyApplied 처리 전 지연을 건 횟수
 * @param rateLimited 처리 한도를 넘어 429를 낸 횟수
 * @param serverErrors 처리하지 않고 500을 낸 횟수
 * @param rejections 업무 거절을 정한 횟수. 거절을 정했어도 요청이 다른 이유로 먼저 실패하면 실제 거절 응답은 나가지 않는다
 * @param delayedApprovals 지연 승인을 정한 횟수
 * @param responseLosses 처리 후 응답을 붙잡았다가 500을 낸 횟수
 * @param tampered 성공 응답 본문을 망가뜨린 횟수
 */
public record FaultStats(
        long requests,
        long latencyApplied,
        long rateLimited,
        long serverErrors,
        long rejections,
        long delayedApprovals,
        long responseLosses,
        long tampered) {}
