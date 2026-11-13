package com.sparta.ticket.reservation.seat.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sparta.ticket.reservation.booking.support.MutableClock;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeat;
import com.sparta.ticket.reservation.seat.domain.ScheduleSeatRepository;
import com.sparta.ticket.reservation.seat.infrastructure.cache.CaffeineSeatLayoutBodyCache;
import com.sparta.ticket.reservation.seat.support.DeferredTasks;
import com.sparta.ticket.reservation.seat.support.MutableTicker;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 구역 목록과 구역 단위 layout·status(#397)를 실제 DB와 캐시로 확인한다. 구역 없는 layout·status는 SelectableSeatApiIntegrationTest가 본다. */
class SeatSectionApiIntegrationTest extends ReservationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 20, 0);
    private static final Long SCHEDULE = 601L;
    private static final String BASE = "/api/seats/schedules/{scheduleId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScheduleSeatRepository scheduleSeatRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableTicker ticker;

    @Autowired
    private DeferredTasks seatSnapshotRefreshTasks;

    @Autowired
    private DeferredTasks seatLayoutCompressTasks;

    private long nextVenueSeatId = 5000;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM p_schedule_seats");
        clock.set(NOW);
        // 스냅샷 캐시와 layout 본문 캐시를 앞 테스트와 나눈다(SelectableSeatApiIntegrationTest와 같은 이유)
        ticker.advance(CaffeineSeatLayoutBodyCache.EXPIRE_AFTER_ACCESS.plusMinutes(1));
        seatSnapshotRefreshTasks.runPending();
        seatLayoutCompressTasks.runPending();
    }

    private Long seat(String section, int number, boolean sellable) {
        return scheduleSeatRepository
                .save(ScheduleSeat.create(
                        SCHEDULE,
                        nextVenueSeatId++,
                        7L,
                        "VIP",
                        sellable ? 150_000 : null,
                        section,
                        "1",
                        number,
                        sellable,
                        2L))
                .getId();
    }

    private void hold(Long seatId) {
        jdbcTemplate.update(
                "UPDATE p_schedule_seats SET status = 'HOLD', reservation_id = 9001, hold_expires_at = ? WHERE id = ?",
                NOW.plusMinutes(5),
                seatId);
    }

    /**
     * 경로와 쿼리를 이미 인코딩된 그대로 보낸다. MockMvc는 문자열 주소를 URI 템플릿으로 보고 %를 다시 인코딩하므로 URI로 넘긴다.
     * 실제 서버(Tomcat)도 받은 쿼리를 한 번 풀어 파라미터로 준다.
     */
    private static URI uri(String pathAndQuery) {
        return URI.create("/api/seats/schedules/" + SCHEDULE + pathAndQuery);
    }

    private String body(String pathAndQuery) throws Exception {
        return mockMvc.perform(get(uri(pathAndQuery)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static String encoded(String section) {
        return URLEncoder.encode(section, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static List<Boolean> bits(String statusBody) {
        int count = JsonPath.read(statusBody, "$.data.seatCount");
        byte[] bits = Base64.getDecoder().decode((String) JsonPath.read(statusBody, "$.data.selectableBitmap"));
        List<Boolean> decoded = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            decoded.add(((bits[i >> 3] >> (i & 7)) & 1) == 1);
        }
        return decoded;
    }

    @Test
    @DisplayName("구역 목록은 구역의 가장 작은 좌석 id 순이고, 좌석 수와 선택 가능 수(선점·판매 제외 좌석 빼고)를 준다. no-store다")
    void sections() throws Exception {
        seat("B", 1, true);
        Long aHeld = seat("A", 1, true);
        seat("B", 2, false);
        seat("A", 2, true);
        hold(aHeld);

        mockMvc.perform(get(BASE + "/sections", SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.scheduleId").value(SCHEDULE))
                .andExpect(jsonPath("$.data.configVersion").value(2))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-01T20:00:00+09:00"))
                .andExpect(jsonPath("$.data.sections.length()").value(2))
                .andExpect(jsonPath("$.data.sections[0].section").value("B"))
                .andExpect(jsonPath("$.data.sections[0].seatCount").value(2))
                .andExpect(jsonPath("$.data.sections[0].selectableCount").value(1))
                .andExpect(jsonPath("$.data.sections[1].section").value("A"))
                .andExpect(jsonPath("$.data.sections[1].seatCount").value(2))
                .andExpect(jsonPath("$.data.sections[1].selectableCount").value(1));
    }

    @Test
    @DisplayName("구역 layout은 그 구역 좌석만 id 오름차순으로 주고, 구역 status의 i번째 비트는 구역 layout의 i번째 좌석이다")
    void sectionLayoutAndStatusMatch() throws Exception {
        // 구역을 섞어 등록해 회차 좌석 id 순서에서 A가 흩어진다
        Long a1 = seat("A", 1, true);
        seat("B", 1, true);
        Long a2 = seat("A", 2, true);
        seat("B", 2, true);
        Long a3 = seat("A", 3, false);
        hold(a2);

        String layout = body("/layout?section=A");
        String status = body("/status?section=A");

        List<Integer> ids = JsonPath.read(layout, "$.data.seats[*].scheduleSeatId");
        assertThat(ids).containsExactly(a1.intValue(), a2.intValue(), a3.intValue());
        assertThat((List<String>) JsonPath.read(layout, "$.data.seats[*].section"))
                .containsOnly("A");
        assertThat(bits(status)).containsExactly(true, false, false);
        assertThat((Integer) JsonPath.read(status, "$.data.configVersion")).isEqualTo(2);
        assertThat((String) JsonPath.read(status, "$.data.asOf")).isEqualTo("2026-11-01T20:00:00+09:00");
    }

    @Test
    @DisplayName("section이 없으면 지금처럼 회차 전체를 준다")
    void noSectionMeansWholeSchedule() throws Exception {
        seat("A", 1, true);
        seat("B", 1, true);

        assertThat((List<?>) JsonPath.read(body("/layout"), "$.data.seats")).hasSize(2);
        assertThat((Integer) JsonPath.read(body("/status"), "$.data.seatCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("한글·공백이 든 구역 이름은 URL 인코딩한 값으로 찾는다")
    void encodedKoreanSectionName() throws Exception {
        seat("1층 A구역", 1, true);
        seat("2층", 1, true);

        String name = JsonPath.read(body("/sections"), "$.data.sections[0].section");
        assertThat(name).isEqualTo("1층 A구역");

        String layout = body("/layout?section=" + encoded(name));
        assertThat((List<String>) JsonPath.read(layout, "$.data.seats[*].section"))
                .containsExactly("1층 A구역");
        assertThat((Integer) JsonPath.read(body("/status?section=" + encoded(name)), "$.data.seatCount"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("없는 구역·대소문자나 공백이 다른 구역은 404 SEAT_NOT_FOUND이고, 받은 값을 응답에 되돌리지 않는다")
    void unknownSectionNotFound() throws Exception {
        seat("A", 1, true);
        seat("b", 1, true);

        for (String path : List.of("/layout", "/status")) {
            for (String section : List.of("C", "a", "B", "A ", "<x>")) {
                mockMvc.perform(get(uri(path + "?section=" + encoded(section))))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("SEAT_NOT_FOUND"))
                        .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                                .doesNotContain("<x>"));
            }
        }
    }

    @Test
    @DisplayName("빈 값이나 20자를 넘는 section은 400 VALIDATION_ERROR다")
    void invalidSectionLength() throws Exception {
        seat("A", 1, true);

        for (String path : List.of("/layout", "/status")) {
            for (String query : List.of("?section=", "?section=" + encoded("가".repeat(21)))) {
                mockMvc.perform(get(uri(path + query)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
            }
        }
        // 20자는 길이 검사를 통과하고, 없는 구역이라 404다
        mockMvc.perform(get(uri("/layout?section=" + encoded("가".repeat(20))))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("없는 구역은 같은 버전 ETag로 와도 304가 아니라 404다")
    void unknownSectionBeforeNotModified() throws Exception {
        seat("A", 1, true);

        mockMvc.perform(get(BASE + "/layout?section=Z", SCHEDULE).header("If-None-Match", "W/\"2\""))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("구역 layout도 약한 ETag(configVersion)·Vary를 주고 같은 버전이면 304다")
    void sectionLayoutEtag() throws Exception {
        seat("A", 1, true);
        seat("B", 1, true);

        mockMvc.perform(get(BASE + "/layout?section=A", SCHEDULE))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "W/\"2\""))
                .andExpect(header().string("Vary", containsString("Accept-Encoding")))
                .andExpect(header().string("Cache-Control", containsString("no-cache")));

        mockMvc.perform(get(BASE + "/layout?section=A", SCHEDULE).header("If-None-Match", "W/\"2\""))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("구역 layout도 압축이 끝나면 gzip을 받고, 풀면 같은 구역의 원본과 같다. 전체 본문과는 다르다")
    void sectionLayoutGzip() throws Exception {
        // 2KB 이상이어야 압축한다. 좌석 1개 약 150B라 30개면 넘는다
        for (int i = 1; i <= 30; i++) {
            seat("A", i, true);
        }
        seat("B", 1, true);
        byte[] identity = mockMvc.perform(get(BASE + "/layout?section=A", SCHEDULE))
                .andExpect(header().doesNotExist("Content-Encoding"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        byte[] whole = mockMvc.perform(get(BASE + "/layout", SCHEDULE))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        seatLayoutCompressTasks.runPending();

        byte[] gzip = mockMvc.perform(get(BASE + "/layout?section=A", SCHEDULE).header("Accept-Encoding", "gzip"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Encoding", "gzip"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            assertThat(in.readAllBytes()).isEqualTo(identity);
        }
        assertThat(identity).isNotEqualTo(whole);
    }
}
