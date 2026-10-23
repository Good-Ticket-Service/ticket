package com.sparta.ticket.common.querydsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.DateTimePath;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringPath;
import com.sparta.ticket.common.exception.InvalidSortException;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class QuerydslSupportTest {

    private final DateTimePath<LocalDateTime> createdAt = Expressions.dateTimePath(LocalDateTime.class, "createdAt");
    private final StringPath name = Expressions.stringPath("name");

    @Test
    @DisplayName("허용 목록에 있는 정렬만 요청 순서와 방향 그대로 QueryDSL 정렬로 바꾼다")
    void convertsAllowedSort() {
        Sort sort = Sort.by(Sort.Order.asc("name"), Sort.Order.desc("createdAt"));

        OrderSpecifier<?>[] orders = QuerydslSupport.toOrderSpecifiers(sort, Map.of("name", name, "createdAt", createdAt));

        assertThat(orders).hasSize(2);
        assertThat(orders[0].getTarget()).isEqualTo(name);
        assertThat(orders[0].getOrder()).isEqualTo(Order.ASC);
        assertThat(orders[1].getTarget()).isEqualTo(createdAt);
        assertThat(orders[1].getOrder()).isEqualTo(Order.DESC);
    }

    @Test
    @DisplayName("허용 목록에 없는 필드로 정렬하면 InvalidSortException")
    void rejectsDisallowedSort() {
        Sort sort = Sort.by(Sort.Direction.DESC, "password");

        assertThatThrownBy(() -> QuerydslSupport.toOrderSpecifiers(sort, Map.of("name", name)))
                .isInstanceOf(InvalidSortException.class)
                .extracting(e -> ((InvalidSortException) e).getProperty())
                .isEqualTo("password");
    }
}
