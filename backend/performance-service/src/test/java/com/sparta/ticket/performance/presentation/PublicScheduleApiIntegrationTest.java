package com.sparta.ticket.performance.presentation;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 공개 회차 목록·상세 API를 실제 PostgreSQL로 호출한다. 명세는 .local/performance/api.md 「공개 조회」다.
 * 데이터는 sql/public-performance-fixture.sql과 sql/public-schedule-fixture.sql이며 기준 시각은 2026-11-01 12:00(KST)이다.
 */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-performance-fixture.sql", "/sql/public-schedule-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PublicScheduleApiIntegrationTest extends PerformanceIntegrationTest {

    private ResultActions schedules(long performanceId) throws Exception {
        return mockMvc.perform(get("/api/performances/" + performanceId + "/schedules"));
    }

    private ResultActions schedule(long scheduleId) throws Exception {
        return mockMvc.perform(get("/api/schedules/" + scheduleId));
    }

    @Nested
    @DisplayName("GET /api/performances/{performanceId}/schedules")
    class GetSchedules {

        @Test
        @DisplayName("삭제 회차를 빼고 취소 회차는 넣어 시작 시각 순으로 준다")
        void listsSchedulesByStartAt() throws Exception {
            schedules(10)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.performanceId").value(10))
                    .andExpect(jsonPath("$.data.schedules[*].id").value(contains(3, 1, 2)))
                    .andExpect(jsonPath("$.data.schedules[*].salesStatus")
                            .value(contains("CANCELED", "BEFORE_OPEN", "BEFORE_OPEN")));
        }

        @Test
        @DisplayName("항목은 명세 필드만 담고, 시각에는 +09:00을 붙인다")
        void itemShape() throws Exception {
            schedules(10)
                    .andExpect(jsonPath("$.data.schedules[1].id").value(1))
                    .andExpect(jsonPath("$.data.schedules[1].performanceId").value(10))
                    .andExpect(jsonPath("$.data.schedules[1].startAt").value("2026-12-01T19:00:00+09:00"))
                    .andExpect(jsonPath("$.data.schedules[1].bookingOpenAt").value("2026-11-10T14:00:00+09:00"))
                    .andExpect(jsonPath("$.data.schedules[1].bookingCloseAt").value("2026-12-01T18:00:00+09:00"))
                    .andExpect(jsonPath("$.data.schedules[1].operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.schedules[1].seatSetupStatus").value("READY"))
                    .andExpect(jsonPath("$.data.schedules[1].salesSuspended").value(false))
                    .andExpect(jsonPath("$.data.schedules[1]", hasKey("canceledAt")))
                    .andExpect(jsonPath("$.data.schedules[1].canceledAt").value(nullValue()))
                    .andExpect(jsonPath("$.data.schedules[1]", not(hasKey("version"))))
                    .andExpect(jsonPath("$.data.schedules[1]", not(hasKey("seatConfigVersion"))))
                    .andExpect(jsonPath("$.data.schedules[1]", not(hasKey("seatSetupError"))))
                    .andExpect(jsonPath("$.data.schedules[1]", not(hasKey("suspensionReason"))))
                    .andExpect(jsonPath("$.data.schedules[1]", not(hasKey("sellerId"))));
        }

        @Test
        @DisplayName("판매 중지·좌석 준비 중·취소 회차의 상태를 주고, 내부 사유는 넣지 않는다")
        void statusesOfOnePerformance() throws Exception {
            schedules(12)
                    .andExpect(jsonPath("$.data.schedules[*].id").value(contains(6, 11, 12, 14)))
                    .andExpect(jsonPath("$.data.schedules[*].salesStatus")
                            .value(contains("ON_SALE", "UNAVAILABLE", "UNAVAILABLE", "CANCELED")))
                    .andExpect(jsonPath("$.data.schedules[1].salesSuspended").value(true))
                    .andExpect(jsonPath("$.data.schedules[2].seatSetupStatus").value("PENDING"))
                    .andExpect(jsonPath("$.data.schedules[3].operationStatus").value("CANCELED"))
                    .andExpect(jsonPath("$.data.schedules[3].canceledAt").value("2026-10-31T10:00:00+09:00"))
                    .andExpect(jsonPath("$.data.schedules[3]", not(hasKey("cancelReason"))));
        }

        @Test
        @DisplayName("회차가 없는 공개 공연은 빈 배열이다")
        void emptySchedules() throws Exception {
            schedules(13)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.performanceId").value(13))
                    .andExpect(jsonPath("$.data.schedules", empty()));
        }

        @Test
        @DisplayName("취소된 공개 공연도 조회하며, 회차는 모두 CANCELED다")
        void canceledPerformance() throws Exception {
            schedules(22)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.schedules[*].id").value(contains(13)))
                    .andExpect(jsonPath("$.data.schedules[0].operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.schedules[0].salesStatus").value("CANCELED"));
        }

        @Test
        @DisplayName("초안·삭제·삭제된 공연장·없는 공연은 404 PERFORMANCE_NOT_FOUND")
        void notFound() throws Exception {
            for (long id : new long[] {20, 21, 23, 999}) {
                schedules(id)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"))
                        .andExpect(jsonPath("$.path").value("/api/performances/" + id + "/schedules"));
            }
        }

        @Test
        @DisplayName("ID가 숫자가 아니면 400 VALIDATION_ERROR")
        void rejectsNonNumericId() throws Exception {
            mockMvc.perform(get("/api/performances/abc/schedules"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Nested
    @DisplayName("GET /api/schedules/{scheduleId}")
    class GetSchedule {

        @Test
        @DisplayName("회차를 명세 형식으로 주고, 판매 기간 중이면 ON_SALE이다")
        void detailShape() throws Exception {
            schedule(6)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(6))
                    .andExpect(jsonPath("$.data.performanceId").value(12))
                    .andExpect(jsonPath("$.data.startAt").value("2027-01-10T19:00:00+09:00"))
                    .andExpect(jsonPath("$.data.bookingOpenAt").value("2026-10-25T14:00:00+09:00"))
                    .andExpect(jsonPath("$.data.bookingCloseAt").value("2027-01-10T18:00:00+09:00"))
                    .andExpect(jsonPath("$.data.operationStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.seatSetupStatus").value("READY"))
                    .andExpect(jsonPath("$.data.salesSuspended").value(false))
                    .andExpect(jsonPath("$.data.salesStatus").value("ON_SALE"))
                    .andExpect(jsonPath("$.data", hasKey("canceledAt")))
                    .andExpect(jsonPath("$.data.canceledAt").value(nullValue()))
                    .andExpect(jsonPath("$.data", not(hasKey("version"))))
                    .andExpect(jsonPath("$.data", not(hasKey("seatSetupError"))));
        }

        @Test
        @DisplayName("판매 상태는 취소 → 마감 → 오픈 전 → 판매 불가 → 판매 중 순서로 정한다")
        void salesStatusPriority() throws Exception {
            schedule(3).andExpect(jsonPath("$.data.salesStatus").value("CANCELED"));
            schedule(13).andExpect(jsonPath("$.data.salesStatus").value("CANCELED"));
            schedule(7).andExpect(jsonPath("$.data.salesStatus").value("CLOSED"));
            schedule(1).andExpect(jsonPath("$.data.salesStatus").value("BEFORE_OPEN"));
            schedule(11).andExpect(jsonPath("$.data.salesStatus").value("UNAVAILABLE"));
            schedule(12).andExpect(jsonPath("$.data.salesStatus").value("UNAVAILABLE"));
            schedule(6).andExpect(jsonPath("$.data.salesStatus").value("ON_SALE"));
        }

        @Test
        @DisplayName("예매 오픈 시각과 같으면 판매 중, 마감 시각과 같으면 마감이다")
        void bookingBoundaries() throws Exception {
            clock.set(LocalDateTime.of(2026, 11, 10, 13, 59, 59));
            schedule(1).andExpect(jsonPath("$.data.salesStatus").value("BEFORE_OPEN"));
            clock.set(LocalDateTime.of(2026, 11, 10, 14, 0));
            schedule(1).andExpect(jsonPath("$.data.salesStatus").value("ON_SALE"));
            clock.set(LocalDateTime.of(2026, 12, 1, 17, 59, 59));
            schedule(1).andExpect(jsonPath("$.data.salesStatus").value("ON_SALE"));
            clock.set(LocalDateTime.of(2026, 12, 1, 18, 0));
            schedule(1).andExpect(jsonPath("$.data.salesStatus").value("CLOSED"));
        }

        @Test
        @DisplayName("판매 기간이 지나면 판매 중지·준비 중이어도 CLOSED다")
        void closedWinsOverUnavailable() throws Exception {
            clock.set(LocalDateTime.of(2027, 1, 12, 18, 0));
            schedule(11).andExpect(jsonPath("$.data.salesStatus").value("CLOSED"));
            schedule(12).andExpect(jsonPath("$.data.salesStatus").value("CLOSED"));
        }

        @Test
        @DisplayName("취소된 공개 공연의 회차도 조회한다")
        void scheduleOfCanceledPerformance() throws Exception {
            schedule(13)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.performanceId").value(22))
                    .andExpect(jsonPath("$.data.operationStatus").value("ACTIVE"));
        }

        @Test
        @DisplayName("삭제 회차, 초안·삭제·삭제된 공연장 공연의 회차, 없는 회차는 404 SCHEDULE_NOT_FOUND")
        void notFound() throws Exception {
            for (long id : new long[] {4, 8, 9, 10, 999}) {
                schedule(id)
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"))
                        .andExpect(jsonPath("$.path").value("/api/schedules/" + id));
            }
        }

        @Test
        @DisplayName("ID가 숫자가 아니면 400 VALIDATION_ERROR")
        void rejectsNonNumericId() throws Exception {
            mockMvc.perform(get("/api/schedules/abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }
}
