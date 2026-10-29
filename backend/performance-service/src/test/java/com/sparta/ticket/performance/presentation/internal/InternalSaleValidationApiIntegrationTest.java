package com.sparta.ticket.performance.presentation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매 검증 내부 API를 실제 PostgreSQL로 호출한다. 명세는 .local/performance/api.md 「POST /internal/schedules/{scheduleId}/sale-validations」다.
 * 데이터는 sql/sale-validation-fixture.sql이며 기준 시각은 2026-11-01 12:00(KST)이다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/sale-validation-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class InternalSaleValidationApiIntegrationTest extends PerformanceIntegrationTest {

    private ResultActions validate(Object scheduleId, String body) throws Exception {
        return mockMvc.perform(post("/internal/schedules/" + scheduleId + "/sale-validations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions validate(long scheduleId, long configVersion, long... venueSeatIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long id : venueSeatIds) {
            ids.append(ids.isEmpty() ? "" : ",").append(id);
        }
        return validate(scheduleId, "{\"configVersion\":" + configVersion + ",\"venueSeatIds\":[" + ids + "]}");
    }

    private ResultActions rejectedWith(long scheduleId, long configVersion, String reason, long... venueSeatIds)
            throws Exception {
        return validate(scheduleId, configVersion, venueSeatIds)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reasonCode").value(reason))
                .andExpect(jsonPath("$.seats", empty()));
    }

    @Nested
    @DisplayName("허용")
    class Allowed {

        @Test
        @DisplayName("판매 가능하면 P21 전체 필드와 요청 순서의 좌석 등급·가격을 주고, ApiResponse로 감싸지 않는다")
        void allowedShape() throws Exception {
            validate(100, 2, 3, 1)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", not(hasKey("data"))))
                    .andExpect(jsonPath("$.allowed").value(true))
                    .andExpect(jsonPath("$", hasKey("reasonCode")))
                    .andExpect(jsonPath("$.reasonCode").value(nullValue()))
                    .andExpect(jsonPath("$.scheduleId").value(100))
                    .andExpect(jsonPath("$.performanceId").value(10))
                    .andExpect(jsonPath("$.sellerId").value(501))
                    .andExpect(jsonPath("$.configVersion").value(2))
                    .andExpect(jsonPath("$.policyVersion").value(3))
                    .andExpect(jsonPath("$.performanceTitle").value("판매 공연"))
                    .andExpect(jsonPath("$.scheduleStartAt").value("2026-12-01T19:00:00+09:00"))
                    .andExpect(jsonPath("$.validatedAt").value("2026-11-01T12:00:00+09:00"))
                    .andExpect(jsonPath("$.seats[*].venueSeatId").value(contains(3, 1)))
                    .andExpect(jsonPath("$.seats[*].venueSeatGradeId").value(contains(2, 1)))
                    .andExpect(jsonPath("$.seats[*].gradeName").value(contains("R", "VIP")))
                    .andExpect(jsonPath("$.seats[*].basePrice").value(contains(100000, 150000)));
        }

        @Test
        @DisplayName("예매 오픈 시각과 같으면 허용, 마감 시각과 같으면 SALES_CLOSED다")
        void bookingBoundaries() throws Exception {
            clock.set(LocalDateTime.of(2026, 11, 10, 14, 0));
            validate(104, 1, 1).andExpect(jsonPath("$.allowed").value(true));
            clock.set(LocalDateTime.of(2026, 12, 5, 18, 0));
            rejectedWith(104, 1, "SALES_CLOSED", 1);
        }
    }

    @Nested
    @DisplayName("업무 거절 (HTTP 200, allowed=false)")
    class Rejected {

        @Test
        @DisplayName("거절도 회차·공연 정보를 모두 채우고 seats만 비운다. configVersion은 회차의 현재 값이다")
        void rejectedShape() throws Exception {
            rejectedWith(100, 1, "SEAT_CONFIG_VERSION_MISMATCH", 1)
                    .andExpect(jsonPath("$.scheduleId").value(100))
                    .andExpect(jsonPath("$.performanceId").value(10))
                    .andExpect(jsonPath("$.sellerId").value(501))
                    .andExpect(jsonPath("$.configVersion").value(2))
                    .andExpect(jsonPath("$.policyVersion").value(3))
                    .andExpect(jsonPath("$.performanceTitle").value("판매 공연"))
                    .andExpect(jsonPath("$.scheduleStartAt").value("2026-12-01T19:00:00+09:00"))
                    .andExpect(jsonPath("$.validatedAt").value("2026-11-01T12:00:00+09:00"));
        }

        @Test
        @DisplayName("공연·회차 상태별 거절 사유: 미공개, 공연 취소, 회차 취소, 판매 중지, 좌석 미준비, 오픈 전, 마감")
        void conditionReasons() throws Exception {
            rejectedWith(106, 1, "PERFORMANCE_NOT_PUBLISHED", 1)
                    .andExpect(jsonPath("$.sellerId").value(502));
            rejectedWith(107, 1, "PERFORMANCE_CANCELED", 1)
                    .andExpect(jsonPath("$.sellerId").value(503));
            rejectedWith(101, 1, "SCHEDULE_CANCELED", 1);
            rejectedWith(102, 1, "SALES_SUSPENDED", 1);
            rejectedWith(103, 1, "SEAT_SETUP_NOT_READY", 1);
            rejectedWith(104, 1, "BEFORE_OPEN", 1);
            rejectedWith(105, 1, "SALES_CLOSED", 1);
        }

        @Test
        @DisplayName("판매 제외·다른 공연장·삭제 좌석·삭제 등급 좌석·없는 좌석이 하나라도 있으면 SEAT_NOT_SELLABLE")
        void seatNotSellable() throws Exception {
            rejectedWith(100, 2, "SEAT_NOT_SELLABLE", 1, 2);
            rejectedWith(100, 2, "SEAT_NOT_SELLABLE", 5);
            rejectedWith(100, 2, "SEAT_NOT_SELLABLE", 6);
            rejectedWith(100, 2, "SEAT_NOT_SELLABLE", 4);
            rejectedWith(100, 2, "SEAT_NOT_SELLABLE", 999);
        }

        @Test
        @DisplayName("삭제된 판매 제외 행과 삭제된 가격 행은 무시한다")
        void ignoresDeletedPolicyAndPrice() throws Exception {
            validate(100, 2, 3)
                    .andExpect(jsonPath("$.allowed").value(true))
                    .andExpect(jsonPath("$.seats[0].basePrice").value(100000));
        }

        @Test
        @DisplayName("구성 버전 불일치는 좌석 판단보다 먼저다")
        void configVersionBeforeSeat() throws Exception {
            rejectedWith(100, 1, "SEAT_CONFIG_VERSION_MISMATCH", 2);
        }

        @Test
        @DisplayName("판매 불가 좌석과 가격 누락 좌석이 섞이면 요청 순서와 관계없이 SEAT_NOT_SELLABLE이다(500보다 먼저)")
        void seatNotSellableBeforeMissingPrice() throws Exception {
            rejectedWith(111, 1, "SEAT_NOT_SELLABLE", 3, 999);
            rejectedWith(111, 1, "SEAT_NOT_SELLABLE", 999, 3);
        }
    }

    @Nested
    @DisplayName("오류")
    class Errors {

        @Test
        @DisplayName("회차·공연·공연장이 없거나 삭제됐으면 404 SCHEDULE_NOT_FOUND")
        void notFound() throws Exception {
            for (long id : new long[] {108, 109, 110, 999}) {
                validate(id, 1, 1)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"))
                        .andExpect(jsonPath("$.path").value("/internal/schedules/" + id + "/sale-validations"));
            }
        }

        @Test
        @DisplayName(
                "configVersion 1 미만·누락, venueSeatIds 비었거나 중복·null 원소, 정수가 아닌 숫자·문자열, Path ID 형식 오류는 400 VALIDATION_ERROR")
        void validationError() throws Exception {
            String[] bodies = {
                "{\"configVersion\":0,\"venueSeatIds\":[1]}",
                "{\"venueSeatIds\":[1]}",
                "{\"configVersion\":1,\"venueSeatIds\":[]}",
                "{\"configVersion\":1}",
                "{\"configVersion\":1,\"venueSeatIds\":[1,1]}",
                "{\"configVersion\":1,\"venueSeatIds\":[1,null]}",
                // 소수를 정수로 잘라 다른 버전·좌석으로 검증하지 않는다
                "{\"configVersion\":2.9,\"venueSeatIds\":[1]}",
                "{\"configVersion\":2,\"venueSeatIds\":[1.9]}",
                "{\"configVersion\":\"2\",\"venueSeatIds\":[1]}",
                "{\"configVersion\":2,\"venueSeatIds\":[\"1\"]}"
            };
            for (String body : bodies) {
                validate(100, body)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
            validate("abc", "{\"configVersion\":1,\"venueSeatIds\":[1]}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        @DisplayName("판매 대상 좌석에 공연 가격이 없으면 불변식 위반이라 500이며 식별자를 로그에 남긴다")
        void missingPriceIsServerError(CapturedOutput output) throws Exception {
            validate(111, 1, 1).andExpect(jsonPath("$.allowed").value(true));
            validate(111, 1, 3)
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
            assertThat(output.getOut()).contains("판매 대상 좌석의 가격 누락").contains("venueSeatId=3");
        }
    }
}
