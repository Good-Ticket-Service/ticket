package com.sparta.ticket.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class SearchPageableArgumentResolverTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SampleController())
                .setCustomArgumentResolvers(new SearchPageableArgumentResolver())
                .build();
    }

    @Test
    @DisplayName("파라미터가 없으면 page 0, size 10, createdAt DESC")
    void defaults() throws Exception {
        mockMvc.perform(get("/search")).andExpect(content().string("0/10/createdAt: DESC"));
    }

    @Test
    @DisplayName("size는 10·30·50만 허용하고 그 외 값은 10으로 보정한다")
    void normalizesSize() throws Exception {
        mockMvc.perform(get("/search").param("size", "30")).andExpect(content().string("0/30/createdAt: DESC"));
        mockMvc.perform(get("/search").param("size", "20")).andExpect(content().string("0/10/createdAt: DESC"));
        mockMvc.perform(get("/search").param("size", "1000"))
                .andExpect(content().string("0/10/createdAt: DESC"));
    }

    @Test
    @DisplayName("요청한 정렬은 그대로 쓴다")
    void keepsRequestedSort() throws Exception {
        mockMvc.perform(get("/search").param("page", "2").param("sort", "name,ASC"))
                .andExpect(content().string("2/10/name: ASC"));
    }

    @RestController
    static class SampleController {

        @GetMapping("/search")
        String search(Pageable pageable) {
            return pageable.getPageNumber() + "/" + pageable.getPageSize() + "/" + pageable.getSort();
        }
    }
}
