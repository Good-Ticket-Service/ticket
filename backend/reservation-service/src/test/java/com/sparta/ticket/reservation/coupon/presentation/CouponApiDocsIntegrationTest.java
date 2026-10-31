package com.sparta.ticket.reservation.coupon.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sparta.ticket.reservation.support.ReservationIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 쿠폰 목록 API의 OpenAPI 문서(/v3/api-docs)를 확인한다(#225).
 * Pageable이 pageable이라는 객체 파라미터 하나로 문서화되면 Swagger UI가 객체 안의 sort 배열을 풀지 못해 sort=["..."]로 보낸다.
 * page·size·sort가 각각의 파라미터이고 sort가 배열이어야 Swagger UI가 sort=a&sort=b로 보낸다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponApiDocsIntegrationTest extends ReservationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String docs;

    @BeforeAll
    void loadDocs() throws Exception {
        docs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private List<Map<String, Object>> parameters(String path) {
        return JsonPath.read(docs, "$.paths['" + path + "'].get.parameters");
    }

    private Map<String, Object> parameter(String path, String name) {
        return parameters(path).stream()
                .filter(p -> name.equals(p.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(path + "에 " + name + " 파라미터가 없다"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/coupons", "/api/coupons/me"})
    @DisplayName("쿠폰 목록·내 쿠폰 목록은 pageable 객체 하나가 아니라 page·size·sort 쿼리 파라미터를 따로 문서화한다")
    void pageableAsSeparateParameters(String path) {
        List<Object> names = parameters(path).stream().map(p -> p.get("name")).toList();

        assertThat(names).contains("page", "size", "sort").doesNotContain("pageable");
        // sort는 배열이라 Swagger UI가 같은 이름을 반복해 보낸다. 셋 다 필수가 아니다
        assertThat(JsonPath.<String>read(parameter(path, "sort"), "$.schema.type"))
                .isEqualTo("array");
        assertThat(parameter(path, "page").get("required")).isNotEqualTo(true);
        assertThat(parameter(path, "size").get("required")).isNotEqualTo(true);
        assertThat(parameter(path, "sort").get("required")).isNotEqualTo(true);
    }

    @Test
    @DisplayName("문서의 size 기본값은 공통 기본값 10이다")
    void sizeDefault() {
        assertThat(JsonPath.<Object>read(parameter("/api/coupons", "size"), "$.schema.default"))
                .isEqualTo(10);
    }
}
