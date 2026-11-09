package com.sparta.ticket.waitingroom.domain;

import java.time.Duration;

/**
 * 입장 처리 한 번에 쓰는 규칙.
 *
 * @param admitPerSecond 초당 입장 인원
 * @param burst 입장할 사람이 없는 동안 쌓아 둘 수 있는 입장 시간. 다음 처리에서 이만큼을 한꺼번에 들여보낼 수 있다
 * @param maxActive 동시에 입장해 있을 수 있는 인원 상한
 * @param leaveAfter 마지막 조회 뒤 이 시간이 지나면 떠난 회원으로 보고 줄에서 뺀다
 * @param entryTtl 입장 유효 시간. 입장 토큰의 만료와 같다
 * @param leaveBatchSize 한 번에 정리할 이탈자 수
 */
public record AdmissionPolicy(
        int admitPerSecond,
        Duration burst,
        int maxActive,
        Duration leaveAfter,
        Duration entryTtl,
        int leaveBatchSize) {}
