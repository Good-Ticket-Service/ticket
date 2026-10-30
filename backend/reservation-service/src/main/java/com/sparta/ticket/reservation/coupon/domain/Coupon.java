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
 * 발급 상태는 컬럼 없이 발급 기간과 현재 시각으로 계산한다. 명세는 docs/api.md 쿠폰 절, docs/tables.md p_coupons다.
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

    /** 검증을 마친 값으로만 부른다. 외부에서는 create로 만든다. */
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

    /**
     * 생성. 발급 종료 시각은 발급 시작 시각과 현재 시각보다 뒤여야 한다.
     * 발급 시작 시각이 과거면 현재 시각으로 저장한다(startNotBefore).
     */
    public static Coupon create(
            String name,
            int discountAmount,
            int totalQuantity,
            LocalDateTime issueStartAt,
            LocalDateTime issueEndAt,
            int validDays,
            LocalDateTime now) {
        // 숫자 항목은 모두 1 이상이어야 한다(DB CHECK 제약과 같은 규칙)
        requirePositive(discountAmount, "할인액은 1 이상이어야 합니다.");
        requirePositive(totalQuantity, "총 수량은 1 이상이어야 합니다.");
        requirePositive(validDays, "유효 일수는 1 이상이어야 합니다.");
        // 과거 시작 시각은 현재로 맞춘 뒤, 발급 기간 순서와 종료 시각이 미래인지 확인한다
        LocalDateTime startAt = startNotBefore(issueStartAt, now);
        requireValidEnd(startAt, issueEndAt, now);
        return new Coupon(name, discountAmount, totalQuantity, startAt, issueEndAt, validDays);
    }

    /**
     * 부분 수정. null인 값은 바꾸지 않는다.
     * 발급이 시작된 뒤에는 이름, 발급 종료 시각, 총 수량만 바꿀 수 있다. 먼저 받은 사람과 나중에 받은 사람의 조건이 달라지지 않게 하기 위해서다.
     * 바꿀 수 없는 항목도 지금과 같은 값이면 바꾸지 않은 것으로 본다.
     * 발급이 끝난(ENDED) 쿠폰은 이름만 바꿀 수 있다. 종료 시각을 미래로 바꾸면 중단한 쿠폰이 다시 열리고,
     * 총 수량은 더 발급하지 않으니 바꿔도 남은 수량 숫자만 달라진다. 이름은 회원의 내 쿠폰 목록에 계속 보여 고칠 수 있게 둔다.
     * 다시 발급하려면 새 쿠폰을 만든다.
     * 시작 전 쿠폰의 발급 시작 시각을 과거로 바꾸면 현재 시각으로 저장한다(startNotBefore).
     * Note: 아무 항목도 보내지 않은 요청은 UpdateCouponRequest가 400으로 막는다.
     */
    public void update(
            String name,
            Integer discountAmount,
            Integer totalQuantity,
            LocalDateTime issueStartAt,
            LocalDateTime issueEndAt,
            Integer validDays,
            LocalDateTime now) {
        // 1) 끝난 쿠폰은 종료 시각·총 수량을 바꿀 수 없다. 종료 시각은 중단한 쿠폰이 다시 열리는 것을 막고,
        //    총 수량은 더 발급하지 않아 의미 없는 남은 수량만 만든다. 나머지 시작 후 금지 항목은 2)가 막는다
        if (issueStatus(now) == CouponIssueStatus.ENDED
                && (changes(issueEndAt, this.issueEndAt) || changes(totalQuantity, this.totalQuantity))) {
            throw new BusinessException(CouponErrorCode.COUPON_ALREADY_CLOSED);
        }
        // 2) 발급이 시작됐으면 할인액·시작 시각·유효 일수는 바꿀 수 없다
        // 발급이 시작됐고(&&) 세 항목 중 하나라도 changes가 true(지금 값과 다른 값으로 바꾸려 함)면 예외다.
        // 보내지 않았거나 같은 값이면 changes가 false라 통과하고, 이름·총 수량·종료 시각은 시작 뒤에도 바꿀 수 있다
        if (isIssueStarted(now)
                && (changes(discountAmount, this.discountAmount)
                        || changes(issueStartAt, this.issueStartAt)
                        || changes(validDays, this.validDays))) {
            throw new BusinessException(CouponErrorCode.COUPON_NOT_MODIFIABLE);
        }
        // 3) 보낸 숫자 값 검증. 총 수량은 이미 발급한 수량보다 작아질 수 없다
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
        // 4) 새 시작 시각이 과거면 현재로 맞춘다. 지금 값과 같으면 그대로 둔다(이미 시작한 쿠폰이 같은 값을 보내도 바뀌지 않게)
        LocalDateTime startAt =
                changes(issueStartAt, this.issueStartAt) ? startNotBefore(issueStartAt, now) : issueStartAt;
        // 기간을 보냈을 때만 바뀐 뒤의 기간을 만들어 순서와 종료 시각을 확인한다. 한쪽만 보내도 다른 쪽은 지금 값과 비교한다.
        // 끝난 쿠폰은 종료 시각이 과거라, 항상 검증하면 이름만 고쳐도 400이 된다
        if (startAt != null || issueEndAt != null) {
            LocalDateTime nextStart = startAt != null ? startAt : this.issueStartAt;
            LocalDateTime nextEnd = issueEndAt != null ? issueEndAt : this.issueEndAt;
            requireValidEnd(nextStart, nextEnd, now);
        }

        // 5) 검증을 모두 통과한 뒤에만 값을 바꾼다. 중간에 실패해도 일부만 바뀐 상태가 남지 않는다. 보낸 항목만 바꾼다
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
        if (startAt != null) {
            this.issueStartAt = startAt;
        }
        if (issueEndAt != null) {
            this.issueEndAt = issueEndAt;
        }
    }

    /**
     * 발급 중단. 발급 종료 시각을 현재로 당긴다. 이미 발급된 쿠폰은 계속 쓸 수 있다.
     * Note: 시작 전 쿠폰은 시작 시각도 현재로 당긴다. 종료만 당기면 시작이 종료보다 늦어져
     * ck_p_coupons_period(issue_start_at <= issue_end_at)를 어기고, 상태도 ENDED가 아니라 UPCOMING으로 계산된다.
     * 그래서 시작 = 종료인 기간이 된다. 생성·수정의 시작 < 종료(requireValidEnd)와 다르지만 의도한 것이다.
     * 끝난 쿠폰은 기간을 바꿀 수 없어(update 1·2단계) 이 기간이 다시 검증되는 일은 없다.
     * 원래 시작 예정 시각은 덮어써져 남지 않는다. 서비스 동작에 쓰이지 않아 따로 보관하지 않는다.
     */
    public void close(LocalDateTime now) {
        CouponIssueStatus status = issueStatus(now);
        // 이미 끝난 쿠폰은 다시 중단할 수 없다
        if (status == CouponIssueStatus.ENDED) {
            throw new BusinessException(CouponErrorCode.COUPON_ALREADY_CLOSED);
        }
        // 시작 전이면 현재 < 시작이라, 종료만 현재로 당기면 시작 > 종료가 된다. 시작도 현재로 당겨 시작 = 종료로 맞춘다
        if (status == CouponIssueStatus.UPCOMING) {
            this.issueStartAt = now;
        }
        // 종료 시각을 현재로 당기면 이 순간부터 ENDED로 계산된다
        this.issueEndAt = now;
    }

    /** 시작 시각 이상, 종료 시각 미만이면 발급 중이다. 종료 시각 정각부터는 끝난 것으로 본다. */
    public CouponIssueStatus issueStatus(LocalDateTime now) {
        // 시작 시각 전
        if (now.isBefore(issueStartAt)) {
            return CouponIssueStatus.UPCOMING;
        }
        // 시작 시각 이후이고 종료 시각 전. 발급 수량 조건부 UPDATE의 기간 조건과 같은 경계다
        if (now.isBefore(issueEndAt)) {
            return CouponIssueStatus.ISSUING;
        }
        // 현재 시각이 종료 시각과 같거나 지났다. 종료 정각부터 끝난 것으로 본다(발급 중단은 종료 시각을 현재로 당겨 여기로 온다)
        return CouponIssueStatus.ENDED;
    }

    /** 발급 시작 시각이 지났는지. 지난 뒤에는 할인액·시작 시각·유효 일수를 바꿀 수 없다. */
    public boolean isIssueStarted(LocalDateTime now) {
        return !now.isBefore(issueStartAt);
    }

    /** 남은 발급 수량. 컬럼 없이 총 수량에서 발급 수량을 빼서 계산한다. */
    public int getRemainingQuantity() {
        return totalQuantity - issuedQuantity;
    }

    /** 요청한 값이 있고, 그 값이 지금 값과 다르면 true. 보내지 않았거나(null) 같은 값이면 바꾸지 않는 것으로 본다. */
    private static boolean changes(Object requested, Object current) {
        return requested != null && !Objects.equals(requested, current);
    }

    /** 1 이상인지 확인한다. 아니면 400 VALIDATION_ERROR. DB의 CHECK 제약(양수)과 같은 규칙이다. */
    private static void requirePositive(int value, String message) {
        if (value <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, message);
        }
    }

    /**
     * 발급 시작 시각이 과거면 현재 시각을 돌려준다. 과거 시작은 "지금 시작"과 같게 동작하므로, 저장값도 실제로 발급이 열린 시각에 맞춘다.
     * Note: 과거를 거절하지 않는다. 클라이언트가 "지금"을 보내도 서버에 닿으면 이미 과거라, 거절하면 바로 시작하는 쿠폰을 만들 수 없다.
     */
    private static LocalDateTime startNotBefore(LocalDateTime issueStartAt, LocalDateTime now) {
        return issueStartAt.isBefore(now) ? now : issueStartAt;
    }

    /** 발급 종료 시각이 시작 시각보다, 그리고 현재보다 뒤인지 확인한다. 아니면 400 VALIDATION_ERROR. */
    private static void requireValidEnd(LocalDateTime issueStartAt, LocalDateTime issueEndAt, LocalDateTime now) {
        // 종료가 시작과 같거나 빠르면 발급할 수 있는 기간이 없다
        if (!issueEndAt.isAfter(issueStartAt)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "발급 종료 시각은 발급 시작 시각 이후여야 합니다.");
        }
        // 종료 시각이 이미 지났으면 만들자마자 끝난 쿠폰이 된다
        if (!issueEndAt.isAfter(now)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "발급 종료 시각은 현재 이후여야 합니다.");
        }
    }
}
