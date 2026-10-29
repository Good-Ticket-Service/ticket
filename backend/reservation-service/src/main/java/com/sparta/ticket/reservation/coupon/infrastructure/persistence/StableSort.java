package com.sparta.ticket.reservation.coupon.infrastructure.persistence;

import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 쿠폰 목록 조회의 정렬 끝에 ID를 보조 키로 붙인다.
 * Note: issueStartAt·issueEndAt·expiresAt처럼 같은 값이 많은 필드로만 정렬하면 DB가 같은 값끼리의 순서를 정하지 않는다.
 * 그러면 페이지를 넘길 때 같은 항목이 다시 나오거나 빠질 수 있어, 유일한 ID로 순서를 끝까지 정한다.
 * 예) issueEndAt이 같은 쿠폰 A~E를 size=2로 나눠 읽을 때, 페이지마다 쿼리가 따로 실행되어 1페이지는 A,B 순서로,
 * 2페이지는 B,C,A,D 순서로 읽히면 2페이지에 A가 다시 나오고 C는 끝내 안 나온다. ID까지 정렬하면 순서가 하나로 고정된다.
 */
final class StableSort {

    private static final String ID = "id";

    private StableSort() {}

    /**
     * 보조 키를 붙여 조회하고, 응답 페이지에는 요청한 Pageable을 그대로 둔다.
     * 응답 sort(PageResponse)는 요청과 같은 정렬을 돌려주기로 했으므로(docs/api.md PageC) 보조 키를 드러내지 않는다.
     */
    static <T> Page<T> query(Pageable pageable, Function<Pageable, Page<T>> query) {
        // 실제 조회는 ID를 붙인 정렬로 한다. query는 호출자가 고른 저장소 쿼리다
        Page<T> page = query.apply(withIdTieBreaker(pageable));
        // 내용·전체 개수는 조회 결과를 쓰고, 페이지 정보(정렬 포함)는 요청 값으로 되돌린다
        return new PageImpl<>(page.getContent(), pageable, page.getTotalElements());
    }

    /** 정렬에 ID가 없으면 마지막 정렬과 같은 방향으로 ID를 덧붙인다. 기본 정렬 createdAt,DESC면 id,DESC가 붙는다. */
    static Pageable withIdTieBreaker(Pageable pageable) {
        Sort sort = pageable.getSort();
        // 페이지 없이 전부 읽거나 이미 ID로 정렬하면 그대로 둔다
        if (pageable.isUnpaged() || sort.getOrderFor(ID) != null) {
            return pageable;
        }
        // 마지막 정렬 방향을 따른다. 정렬이 없으면 오름차순이다
        // 기본 정렬(createdAt,DESC)에서 같은 시각이면 나중에 만든(ID가 큰) 행이 먼저 와, 최신순이라는 뜻이 유지된다
        Sort.Direction direction = sort.stream()
                .reduce((first, second) -> second)
                .map(Sort.Order::getDirection)
                .orElse(Sort.Direction.ASC);
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(Sort.by(direction, ID)));
    }
}
