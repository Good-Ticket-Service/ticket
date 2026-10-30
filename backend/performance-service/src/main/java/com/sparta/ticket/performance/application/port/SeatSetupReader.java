package com.sparta.ticket.performance.application.port;

import com.sparta.ticket.performance.application.dto.SeatSetupCommand;
import java.util.List;

/** 좌석 준비 요청에 담을 좌석을 읽는 포트. 공연장 좌석에 고정 등급, 공연별 가격, 판매 제외를 조합한다. */
public interface SeatSetupReader {

    /** 공연의 공연장 좌석 전체. 삭제된 좌석과 등급이 삭제된 좌석은 뺀다(공개 좌석 배치와 같은 기준). */
    List<SeatSetupCommand.Line> findSeatLines(Long performanceId);
}
