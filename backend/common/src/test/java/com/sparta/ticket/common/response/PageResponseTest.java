package com.sparta.ticket.common.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class PageResponseTest {

    @Test
    @DisplayName("Page를 공통 목록 응답 형식으로 바꾸고 정렬은 '필드,방향' 문자열로 표현한다")
    void fromPage() {
        PageRequest pageable = PageRequest.of(1, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        PageImpl<String> page = new PageImpl<>(List.of("a", "b"), pageable, 42);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(42);
        assertThat(response.totalPages()).isEqualTo(5);
        assertThat(response.sort()).isEqualTo("createdAt,DESC");
    }

    @Test
    @DisplayName("mapper를 주면 내용만 바꾸고 페이지 정보는 그대로 둔다")
    void fromPageWithMapper() {
        PageImpl<String> page = new PageImpl<>(List.of("a", "bb"), PageRequest.of(0, 10), 2);

        PageResponse<Integer> response = PageResponse.from(page, String::length);

        assertThat(response.content()).containsExactly(1, 2);
        assertThat(response.totalElements()).isEqualTo(2);
    }
}
