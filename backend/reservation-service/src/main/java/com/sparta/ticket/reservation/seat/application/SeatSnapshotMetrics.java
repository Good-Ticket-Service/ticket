package com.sparta.ticket.reservation.seat.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.domain.ReservationErrorCode;
import com.sparta.ticket.reservation.seat.application.dto.SeatMapSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 좌석 배치도 스냅샷 지표(#311). Prometheus가 /actuator/prometheus로 수집한다.
 * get은 요청 스레드가 스냅샷을 받기까지 기다린 시간이고, load는 스냅샷을 만드는 데 걸린 시간이다.
 * 만료 순간 요청이 로딩을 기다리면 get이 load만큼 길어지고, 기존 값을 바로 주면 get이 load와 떨어진다. 개선 전후를 이 둘로 비교한다.
 *
 * Note: 회차 ID 태그를 붙이지 않는다. 회차 수만큼 시계열이 늘어나고, 측정은 회차 하나로 하므로 합으로 충분하다.
 */
@Component
public class SeatSnapshotMetrics {

    private final Timer get;
    private final Timer loadSuccess;
    private final Timer loadNotFound;
    private final Timer loadError;

    public SeatSnapshotMetrics(MeterRegistry meters) {
        this.get = Timer.builder("reservation.seat.snapshot.get")
                .description("요청 스레드가 좌석 배치도 스냅샷을 받기까지 기다린 시간")
                .publishPercentileHistogram()
                .register(meters);
        this.loadSuccess = loadTimer(meters, "success");
        this.loadNotFound = loadTimer(meters, "not_found");
        this.loadError = loadTimer(meters, "error");
    }

    private static Timer loadTimer(MeterRegistry meters, String result) {
        return Timer.builder("reservation.seat.snapshot.load")
                .description("좌석 배치도 스냅샷을 만드는 데 걸린 시간(DB 연결 대기·조회·비트맵 계산·구역 나누기 포함)")
                .tag("result", result)
                .publishPercentileHistogram()
                .register(meters);
    }

    /** 요청 스레드가 스냅샷을 받는 시간을 잰다. 예외로 끝나도 기다린 시간은 남긴다. */
    SeatMapSnapshot timeGet(Supplier<SeatMapSnapshot> getter) {
        Timer.Sample sample = Timer.start();
        try {
            return getter.get();
        } finally {
            sample.stop(get);
        }
    }

    /**
     * 스냅샷을 만드는 시간을 결과별로 잰다. 좌석이 아직 없는 회차(404 SEAT_NOT_FOUND)는 정상 응답이라 not_found로 따로 센다.
     * error에는 DB 연결 실패처럼 서버가 스냅샷을 만들지 못한 경우만 남긴다.
     */
    SeatMapSnapshot timeLoad(Supplier<SeatMapSnapshot> loader) {
        Timer.Sample sample = Timer.start();
        try {
            SeatMapSnapshot snapshot = loader.get();
            sample.stop(loadSuccess);
            return snapshot;
        } catch (BusinessException e) {
            sample.stop(e.getErrorCode() == ReservationErrorCode.SEAT_NOT_FOUND ? loadNotFound : loadError);
            throw e;
        } catch (RuntimeException e) {
            sample.stop(loadError);
            throw e;
        }
    }
}
