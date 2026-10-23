package com.sparta.ticket.common.page;

import com.sparta.ticket.common.exception.InvalidSortException;
import java.util.Set;
import org.springframework.data.domain.Pageable;

/**
 * Spring Data 리포지토리에 Pageable을 그대로 넘길 때 쓰는 정렬 허용 목록 검사. QueryDSL로 정렬할 때는 QuerydslSupport를 쓴다.
 *
 * Note: Entity에 있는 필드면 Spring Data는 어떤 필드든 정렬해 준다. password처럼 정렬 순서로 값을 추측할 수 있는 필드를
 * 막으려면 API마다 허용 필드를 정해 검사해야 한다.
 */
public final class SortAllowList {

    private SortAllowList() {}

    /** 허용 목록 밖의 정렬 필드가 있으면 InvalidSortException. 예: {@code SortAllowList.check(pageable, Set.of("createdAt", "name"))} */
    public static Pageable check(Pageable pageable, Set<String> allowed) {
        pageable.getSort().forEach(order -> {
            if (!allowed.contains(order.getProperty())) {
                throw new InvalidSortException(order.getProperty());
            }
        });
        return pageable;
    }
}
