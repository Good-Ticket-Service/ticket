package com.sparta.ticket.performance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.ResultActions;

/** P20 공개 배치를 독립 fixture와 실제 PostgreSQL로 검증한다. */
@Sql(
        value = {"/sql/truncate.sql", "/sql/public-seat-layout-fixture.sql"},
        config = @SqlConfig(encoding = "UTF-8"))
class PublicSeatLayoutApiIntegrationTest extends PerformanceIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private ResultActions layout(long id) throws Exception {
        return mockMvc.perform(get("/api/performances/" + id + "/seat-layout"));
    }

    @Test
    @DisplayName("구역·열 길이·열·좌석 번호 순서로 정렬하며 숫자 열은 1, 2, 10 순서다")
    void sortsBySectionThenRowLengthThenRowThenNumericSeat() throws Exception {
        layout(100)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.performanceId").value(100))
                .andExpect(jsonPath("$.data.venue.id").value(101))
                .andExpect(jsonPath("$.data.venue.name").value("배치 공연장"))
                .andExpect(jsonPath("$.data.venue.address").value("서울 주소"))
                .andExpect(jsonPath("$.data.venue.region").value("서울"))
                .andExpect(jsonPath("$.data.seats[*].id").value(contains(108, 103, 101, 102, 104, 107)))
                .andExpect(jsonPath("$.data.seats[*].rowLabel").value(contains("1", "2", "2", "10", "1", "2")))
                .andExpect(jsonPath("$.data.seats[*].seatNumber").value(contains(1, 1, 2, 1, 1, 1)));
    }

    @Test
    @DisplayName("삭제 행을 무시하고 공연 가격과 판매 제외를 조합한다")
    void combinesPricesAndExclusionsIgnoringDeletedRows() throws Exception {
        layout(100)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.seats[*].isSellable").value(contains(true, false, true, true, false, false)))
                .andExpect(jsonPath("$.data.seats[0].venueSeatGradeId").value(101))
                .andExpect(jsonPath("$.data.seats[0].gradeName").value("VIP"))
                .andExpect(jsonPath("$.data.seats[0].basePrice").value(150000))
                .andExpect(jsonPath("$.data.seats[1].basePrice").value(150000))
                .andExpect(jsonPath("$.data.seats[2].basePrice").value(150000))
                .andExpect(jsonPath("$.data.seats[3].basePrice").value(150000))
                .andExpect(jsonPath("$.data.seats[4]", hasKey("basePrice")))
                .andExpect(jsonPath("$.data.seats[4].basePrice").value(nullValue()))
                .andExpect(jsonPath("$.data.seats[5]", hasKey("basePrice")))
                .andExpect(jsonPath("$.data.seats[5].basePrice").value(nullValue()));
    }

    @Test
    @DisplayName("응답은 P20 필드만 포함하며 내부 정보가 없다")
    void exposesOnlyContractFields() throws Exception {
        String body = layout(100).andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        assertThat(fields(data)).containsExactlyInAnyOrder("performanceId", "venue", "seats");
        assertThat(fields(data.get("venue"))).containsExactlyInAnyOrder("id", "name", "address", "region");
        for (JsonNode seat : data.get("seats")) {
            assertThat(fields(seat))
                    .containsExactlyInAnyOrder(
                            "id",
                            "section",
                            "rowLabel",
                            "seatNumber",
                            "venueSeatGradeId",
                            "gradeName",
                            "isSellable",
                            "basePrice");
        }
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("취소된 공개 공연도 자체 가격·제외 정책으로 조회한다")
    void canceledPublishedPerformanceIsVisibleWithItsOwnPricesAndPolicies() throws Exception {
        layout(101)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seats[*].isSellable").value(contains(true, true, true, true, true, true)))
                .andExpect(jsonPath("$.data.seats[*].basePrice")
                        .value(contains(120000, 120000, 120000, 120000, 120000, 120000)));
    }

    @Test
    @DisplayName("좌석이 없는 공연장은 빈 좌석 배열을 반환한다")
    void emptyVenueHasEmptySeats() throws Exception {
        layout(106).andExpect(status().isOk()).andExpect(jsonPath("$.data.seats", empty()));
    }

    @Test
    @DisplayName("초안·삭제·미존재 공연과 삭제된 공연장의 공연은 404다")
    void hidesDraftDeletedMissingAndDeletedVenuePerformances() throws Exception {
        for (long id : new long[] {102, 103, 104, 999}) {
            layout(id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.path").value("/api/performances/" + id + "/seat-layout"));
        }
    }

    @Test
    @DisplayName("숫자가 아니거나 long 범위를 넘는 ID는 400 VALIDATION_ERROR다")
    void rejectsInvalidPathId() throws Exception {
        for (String id : new String[] {"abc", "9223372036854775808"}) {
            mockMvc.perform(get("/api/performances/" + id + "/seat-layout"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("판매 대상 가격 누락은 배치 전체 500이며 식별자를 로그에 남긴다")
    void missingSellablePriceReturnsGenericErrorAndLogsIdentifiers(CapturedOutput output) throws Exception {
        layout(105)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
        assertThat(output.getOut()).contains("performanceId=105", "seatId=108", "venueSeatGradeId=101");
    }
}
