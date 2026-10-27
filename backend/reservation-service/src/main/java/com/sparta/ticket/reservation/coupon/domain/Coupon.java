package com.sparta.ticket.reservation.coupon.domain;

import com.sparta.ticket.common.entity.BaseEntity;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 관리자가 만든 쿠폰 정의. 회원이 받은 쿠폰 한 장 한 장은 회원 쿠폰(p_user_coupons)이다.
 * 발급 상태는 컬럼 없이 발급 기간과 현재 시각으로 계산한다. 명세는 docs/coupon/api.md, docs/coupon/tables.md다.
 */
@Getter
@Entity
@Table(name = "p_coupons")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coupon_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int discountAmount;

    @Column(nullable = false)
    private int totalQuantity;

    /**
     * 선착순 발급의 조건부 UPDATE로만 올린다. Entity는 값을 바꾸지 않는다.
     * Note: JPA는 변경 감지로 UPDATE할 때 모든 컬럼을 다시 쓴다. 관리자가 쿠폰을 수정하는 사이 발급이 이 값을 올리면,
     * 수정 트랜잭션이 읽어 둔 옛 값으로 덮어써 발급 수량이 줄어든다. updatable = false로 Entity의 UPDATE에서 이 컬럼을 뺀다.
     */
    @Column(nullable = false, updatable = false)
    private int issuedQuantity;

    @Column(nullable = false)
    private LocalDateTime issueStartAt;

    @Column(nullable = false)
    private LocalDateTime issueEndAt;

    @Column(nullable = false)
    private int validDays;

    private Coupon(
            String name,
            int discountAmount,
            int totalQuantity,
            LocalDateTime issueStartAt,
            LocalDateTime issueEndAt,
            int validDays) {
        this.name = name;
        this.discountAmount = discountAmount;
        this.totalQuantity = totalQuantity;
        this.issuedQuantity = 0;
        this.issueStartAt = issueStartAt;
        this.issueEndAt = issueEndAt;
        this.validDays = validDays;
    }

    /** 생성. 발급 종료 시각은 발급 시작 시각과 현재 시각보다 뒤여야 한다. */
    public static Coupon create(
            String name,
            int discountAmount,
            int totalQuantity,
            LocalDateTime issueStartAt,
            LocalDateTime issueEndAt,
            int validDays,
            LocalDateTime now) {
        requirePositive(discountAmount, "할인액은 1 이상이어야 합니다.");
        requirePositive(totalQuantity, "총 수량은 1 이상이어야 합니다.");
        requirePositive(validDays, "유효 일수는 1 이상이어야 합니다.");
        requireValidEnd(issueStartAt, issueEndAt, now);
        return new Coupon(name, discountAmount, totalQuantity, issueStartAt, issueEndAt, validDays);
    }

    /**
     * 부분 수정. null인 값은 바꾸지 않는다.
     * 발급이 시작된 뒤에는 이름, 발급 종료 시각, 총 수량만 바꿀 수 있다. 먼저 받은 사람과 나중에 받은 사람의 조건이 달라지지 않게 하기 위해서다.
     * 바꿀 수 없는 항목도 지금과 같은 값이면 바꾸지 않은 것으로 본다.
     */
    public void update(
            String name,
            Integer discountAmount,
            Integer totalQuantity,
            LocalDateTime issueStartAt,
            LocalDateTime issueEndAt,
            Integer validDays,
            LocalDateTime now) {
        if (isIssueStarted(now)
                && (changes(discountAmount, this.discountAmount)
                        || changes(issueStartAt, this.issueStartAt)
                        || changes(validDays, this.validDays))) {
            throw new BusinessException(CouponErrorCode.COUPON_NOT_MODIFIABLE);
        }
        if (discountAmount != null) {
            requirePositive(discountAmount, "할인액은 1 이상이어야 합니다.");
        }
        if (validDays != null) {
            requirePositive(validDays, "유효 일수는 1 이상이어야 합니다.");
        }
        if (totalQuantity != null) {
            requirePositive(totalQuantity, "총 수량은 1 이상이어야 합니다.");
            if (totalQuantity < issuedQuantity) {
                throw new BusinessException(CouponErrorCode.COUPON_QUANTITY_BELOW_ISSUED);
            }
        }
        LocalDateTime nextStart = issueStartAt != null ? issueStartAt : this.issueStartAt;
        LocalDateTime nextEnd = issueEndAt != null ? issueEndAt : this.issueEndAt;
        if (issueStartAt != null || issueEndAt != null) {
            requireValidEnd(nextStart, nextEnd, now);
        }

        if (name != null) {
            this.name = name;
        }
        if (discountAmount != null) {
            this.discountAmount = discountAmount;
        }
        if (totalQuantity != null) {
            this.totalQuantity = totalQuantity;
        }
        if (validDays != null) {
            this.validDays = validDays;
        }
        this.issueStartAt = nextStart;
        this.issueEndAt = nextEnd;
    }

    /**
     * 발급 중단. 발급 종료 시각을 현재로 당긴다. 이미 발급된 쿠폰은 계속 쓸 수 있다.
     * Note: 시작 전 쿠폰은 시작 시각도 현재로 당긴다. 종료만 당기면 시작이 종료보다 늦어져
     * ck_p_coupons_period(issue_start_at <= issue_end_at)를 어기고, 상태도 ENDED가 아니라 UPCOMING으로 계산된다.
     */
    public void close(LocalDateTime now) {
        CouponIssueStatus status = issueStatus(now);
        if (status == CouponIssueStatus.ENDED) {
            throw new BusinessException(CouponErrorCode.COUPON_ALREADY_CLOSED);
        }
        if (status == CouponIssueStatus.UPCOMING) {
            this.issueStartAt = now;
        }
        this.issueEndAt = now;
    }

    /** 시작 시각 이상, 종료 시각 미만이면 발급 중이다. 종료 시각 정각부터는 끝난 것으로 본다. */
    public CouponIssueStatus issueStatus(LocalDateTime now) {
        if (now.isBefore(issueStartAt)) {
            return CouponIssueStatus.UPCOMING;
        }
        if (now.isBefore(issueEndAt)) {
            return CouponIssueStatus.ISSUING;
        }
        return CouponIssueStatus.ENDED;
    }

    public boolean isIssueStarted(LocalDateTime now) {
        return !now.isBefore(issueStartAt);
    }

    public int getRemainingQuantity() {
        return totalQuantity - issuedQuantity;
    }

    private static boolean changes(Object requested, Object current) {
        return requested != null && !Objects.equals(requested, current);
    }

    private static void requirePositive(int value, String message) {
        if (value <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, message);
        }
    }

    private static void requireValidEnd(LocalDateTime issueStartAt, LocalDateTime issueEndAt, LocalDateTime now) {
        if (!issueEndAt.isAfter(issueStartAt)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "발급 종료 시각은 발급 시작 시각 이후여야 합니다.");
        }
        if (!issueEndAt.isAfter(now)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "발급 종료 시각은 현재 이후여야 합니다.");
        }
    }
}
