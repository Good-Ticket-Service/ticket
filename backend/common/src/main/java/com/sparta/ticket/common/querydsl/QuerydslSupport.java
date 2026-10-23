package com.sparta.ticket.common.querydsl;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.sparta.ticket.common.exception.InvalidSortException;
import java.util.Map;
import org.springframework.data.domain.Sort;

/**
 * QueryDSL 검색 쿼리 보조. Q클래스와 쿼리 자체는 각 서비스의 infrastructure 계층에 둔다.
 *
 * Note: 사용자가 보낸 정렬 문자열을 경로로 바꿔 orderBy에 넣으면 의도하지 않은 필드(연관 엔티티 포함)로 정렬할 수 있다
 * (QueryDSL 정렬 취약점 CVE-2024-49203). 서비스가 정한 허용 목록(필드명 → Q클래스 경로)으로만 변환한다.
 */
public final class QuerydslSupport {

    private QuerydslSupport() {
    }

    /**
     * Pageable의 정렬을 QueryDSL 정렬로 바꾼다. 허용 목록에 없는 필드는 InvalidSortException(400 VALIDATION_ERROR).
     *
     * @param allowed 정렬 필드명 → Q클래스 경로. 예: {@code Map.of("createdAt", event.createdAt, "name", event.name)}
     */
    public static OrderSpecifier<?>[] toOrderSpecifiers(Sort sort, Map<String, ? extends ComparableExpressionBase<?>> allowed) {
        return sort.stream()
                .map(order -> {
                    ComparableExpressionBase<?> path = allowed.get(order.getProperty());
                    if (path == null) {
                        throw new InvalidSortException(order.getProperty());
                    }
                    return new OrderSpecifier<>(order.isAscending() ? Order.ASC : Order.DESC, path);
                })
                .toArray(OrderSpecifier<?>[]::new);
    }
}
