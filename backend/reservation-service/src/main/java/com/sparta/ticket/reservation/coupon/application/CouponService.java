package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.reservation.coupon.application.dto.CouponResult;
import com.sparta.ticket.reservation.coupon.application.dto.CreateCouponCommand;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자의 쿠폰 생성·수정·발급 중단·삭제. 업무 규칙은 Coupon이 갖고, 이 클래스는 조회·저장과 현재 시각을 맡는다. */
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;

    @Transactional
    public CouponResult create(CreateCouponCommand command) {
        LocalDateTime now = now();
        Coupon coupon = Coupon.create(
                command.name(),
                command.discountAmount(),
                command.totalQuantity(),
                command.issueStartAt(),
                command.issueEndAt(),
                command.validDays(),
                now);
        return CouponResult.of(couponRepository.save(coupon), now);
    }

    /**
     * Note: 초 단위로 자른다. 응답 시각 형식이 yyyy-MM-ddTHH:mm:ss이고, 발급 중단은 이 값을 종료 시각으로 저장한다.
     * 자르지 않으면 나노초가 응답에 붙고, PostgreSQL TIMESTAMP(마이크로초)에 저장한 값과 메모리의 값이 달라진다.
     */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
