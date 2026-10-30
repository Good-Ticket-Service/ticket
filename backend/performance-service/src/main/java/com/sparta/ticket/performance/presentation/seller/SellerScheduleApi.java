package com.sparta.ticket.performance.presentation.seller;

import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.application.dto.NewSchedule;
import com.sparta.ticket.performance.application.dto.SellerScheduleDetail;
import com.sparta.ticket.performance.application.service.SellerScheduleService;
import com.sparta.ticket.performance.presentation.seller.dto.ScheduleCreateRequest;
import com.sparta.ticket.performance.presentation.seller.dto.SellerScheduleResponse;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 판매자 회차 명령 API. 바인딩과 Swagger 문서는 ApiSpec에 둔다. 요청 시각은 여기서 KST로 바꾼다. */
@RestController
@RequiredArgsConstructor
@RequireRole(Role.SELLER)
@RequestMapping("/api/seller")
public class SellerScheduleApi implements SellerScheduleApiSpec {

    private static final String SUCCESS = "SUCCESS";
    private static final String SCHEDULE_LOCATION = "/api/seller/schedules/";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SellerScheduleService service;

    @Override
    @PostMapping("/performances/{performanceId}/schedules")
    public ResponseEntity<ApiResponse<SellerScheduleResponse>> register(
            UserContext user, Long performanceId, ScheduleCreateRequest request) {
        SellerScheduleDetail schedule = service.register(
                user.userId(),
                performanceId,
                new NewSchedule(
                        toKst(request.startAt()), toKst(request.bookingOpenAt()), toKst(request.bookingCloseAt())));
        return ResponseEntity.created(URI.create(SCHEDULE_LOCATION + schedule.id()))
                .body(ApiResponse.created(SUCCESS, SellerScheduleResponse.from(schedule)));
    }

    /**
     * 요청 시각을 KST로 바꾼다. 다른 오프셋으로 와도 같은 순간이다.
     * Note: 컬럼이 TIMESTAMP(3)라 밀리초 아래를 버린다. 버리지 않으면 저장된 값과 중복 확인에 쓴 값이 달라질 수 있다.
     */
    private static LocalDateTime toKst(OffsetDateTime time) {
        return time.atZoneSameInstant(SEOUL).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS);
    }
}
