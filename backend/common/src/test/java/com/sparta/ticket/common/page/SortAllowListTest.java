package com.sparta.ticket.common.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparta.ticket.common.exception.InvalidSortException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class SortAllowListTest {

    @Test
    @DisplayName("허용된 필드만으로 정렬하면 Pageable을 그대로 돌려준다")
    void passesAllowedSort() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by("createdAt", "name"));

        assertThat(SortAllowList.check(pageable, Set.of("createdAt", "name"))).isSameAs(pageable);
    }

    @Test
    @DisplayName("허용되지 않은 필드가 하나라도 있으면 InvalidSortException")
    void rejectsDisallowedSort() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by("createdAt", "password"));

        assertThatThrownBy(() -> SortAllowList.check(pageable, Set.of("createdAt")))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("password");
    }
}
