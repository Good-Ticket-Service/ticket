package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.coupon.application.dto.CouponResult;
import com.sparta.ticket.reservation.coupon.application.dto.CreateCouponCommand;
import com.sparta.ticket.reservation.coupon.application.dto.UpdateCouponCommand;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponErrorCode;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate.DeleteMark;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate.TotalChange;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.IntConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 관리자의 쿠폰 생성·수정·발급 중단·삭제. 업무 규칙은 Coupon이 갖고, 이 클래스는 조회·저장과 현재 시각, 발급 판단(Redis) 연동을 맡는다.
 * Redis에 사본이 있는 값(총 수량, 삭제 여부)을 바꾸는 수정·삭제는 Redis에서 먼저 판단·변경하고 DB에 저장한다(#300).
 * 받은 회원 수와 비교하는 판단을 발급과 같은 곳(Redis Lua)에서 해야 그사이 발급이 끼어들지 않기 때문이다.
 * DB 저장이 롤백되면 Redis를 되돌린다. 이름·기간 수정과 발급 중단은 Redis 사본이 없어 DB만 바꾼다.
 * Note: 관리자 요청끼리는 지금처럼 쿠폰 행을 잠가(lockById) 차례로 처리한다. 발급은 이 잠금을 쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponIssueGate issueGate;
    private final CouponIssueState issueState;
    private final Clock clock;

    /** 쿠폰을 만들어 저장하고, 계산 값(남은 수량, 발급 상태)을 붙여 돌려준다. 받은 회원 0명으로 시작한다. */
    @Transactional
    public CouponResult create(CreateCouponCommand command) {
        LocalDateTime now = now();
        // 검증과 생성은 Coupon이 한다. 규칙에 어긋나면 여기서 예외가 난다
        Coupon coupon = Coupon.create(
                command.name(),
                command.discountPolicy(),
                command.totalQuantity(),
                command.issueStartAt(),
                command.issueEndAt(),
                command.validDays(),
                now);
        // 1) 저장하면 DB가 만든 ID가 채워진다(IDENTITY라 INSERT가 바로 나간다). 응답의 발급 상태는 같은 now로 계산한다
        Coupon saved = couponRepository.save(coupon);
        // 2) 커밋 전에 발급 판단용 Redis 정보를 받은 회원 0명으로 새로 만든다(같은 ID로 남은 이전 값을 지운다)
        //    커밋 전에는 다른 요청이 이 쿠폰을 못 봐(발급은 404) 첫 발급과 겹치지 않는다. 커밋 뒤에 만들면 그사이 첫 발급이
        //    DB에서 채운 회원 기록을 reset.lua가 지워 초과 발급이 된다(#340)
        // Note: 로컬은 ddl-auto: create라 재시작하면 쿠폰 ID가 1부터 다시 쓰이는데, Redis에는 이전 쿠폰의 회원이 남아 있다
        // Note: 커밋이 실패하면 Redis에 이 ID의 정보 키만 남는다. 발급은 DB에서 쿠폰을 먼저 찾아 404라 닿지 않고,
        //       ID(IDENTITY)는 롤백해도 다시 쓰이지 않으며, 키는 발급 종료 + 1일에 Redis가 지운다(#323)
        // Note: Redis 장애로 못 만들어도 생성은 성공으로 둔다. 첫 발급 때 DB에서 채운다(정보 키가 없을 때만).
        //       명령 제한 시간(1초, config-repo)이 있어 트랜잭션이 DB 연결을 잡은 채 오래 기다리지 않는다
        runQuietly(
                () -> issueGate.reset(saved.getId(), saved.getTotalQuantity(), issueState.ttl(saved.getIssueEndAt())));
        return CouponResult.of(saved, saved.getTotalQuantity(), now);
    }

    /**
     * 보낸 항목만 바꾼다. 바꿀 수 있는지는 Coupon.update가 판단하고, 바뀐 값은 트랜잭션이 끝날 때 변경 감지로 저장된다.
     * 총 수량을 바꾸면 Redis에서 받은 회원 수와 비교하고 바꾼다. 받은 회원 수보다 적으면 409 COUPON_QUANTITY_BELOW_ISSUED다.
     */
    @Transactional
    public CouponResult update(Long couponId, UpdateCouponCommand command) {
        LocalDateTime now = now();
        // 1) 행을 잠그고 읽어 다른 관리자 요청과 차례를 맞춘다
        Coupon coupon = lockCoupon(couponId);
        int oldTotal = coupon.getTotalQuantity();
        // 2) 바꿀 수 있는지 판단과 값 변경은 Coupon이 한다. 저장은 커밋 때 변경 감지로 된다
        coupon.update(
                command.name(),
                command.discountPolicy(),
                command.totalQuantity(),
                command.issueStartAt(),
                command.issueEndAt(),
                command.validDays(),
                now);
        // 3) 총 수량이 바뀌었으면 Redis에서 받은 회원 수와 비교하고 바꾼다. 거절되면 예외로 DB 변경도 롤백된다
        int newTotal = coupon.getTotalQuantity();
        if (newTotal != oldTotal) {
            changeTotal(coupon, oldTotal, newTotal);
        }
        return CouponResult.of(coupon, issueState.remainingQuantity(coupon), now);
    }

    /** 발급 종료 시각을 현재로 당긴다. 기간은 DB에서만 확인하므로 Redis는 바꾸지 않는다. */
    @Transactional
    public CouponResult close(Long couponId) {
        LocalDateTime now = now();
        // 다른 관리자 요청과 겹치지 않도록 행을 잠그고 읽은 뒤 종료 시각을 당긴다
        Coupon coupon = lockCoupon(couponId);
        coupon.close(now);
        return CouponResult.of(coupon, issueState.remainingQuantity(coupon), now);
    }

    /**
     * 아무도 받지 않은 쿠폰만 논리 삭제한다. 받았는지는 Redis에서 삭제 표시와 함께 판단한다(받은 회원이 있으면 409 COUPON_NOT_DELETABLE).
     * 삭제 표시와 발급 판단이 같은 Redis 키를 보므로 삭제와 첫 발급이 동시에 와도 하나만 성공한다.
     * - 삭제가 먼저면 발급은 삭제 표시를 보고 404다
     * - 발급이 먼저면 삭제는 받은 회원 1명을 보고 409다
     * 없거나 이미 삭제된 쿠폰은 잠금 조회가 비어 404다.
     */
    @Transactional
    public void delete(Long couponId, String username) {
        Coupon coupon = lockCoupon(couponId);
        // 1) Redis에서 받은 회원이 없을 때만 삭제 표시를 남긴다. 정보가 없으면 DB에서 채운 뒤 다시 한다
        DeleteMark mark = issueGate.markDeleted(couponId);
        if (mark == DeleteMark.NOT_LOADED) {
            issueState.ensureLoaded(couponId, coupon.getTotalQuantity(), coupon.getIssueEndAt());
            mark = issueGate.markDeleted(couponId);
        }
        if (mark == DeleteMark.ISSUED_EXISTS) {
            throw new BusinessException(CouponErrorCode.COUPON_NOT_DELETABLE);
        }
        if (mark == DeleteMark.NOT_LOADED) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        // 2) DB 삭제가 롤백되면 삭제 표시를 지운다. 커밋 결과를 모르면 표시를 남긴다(발급이 막히는 안전한 쪽)
        afterCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                issueGate.unmarkDeleted(couponId);
            }
        });
        // 3) 삭제 기록은 Coupon이 한다. 저장은 커밋 때 변경 감지로 되고 updated_at·updated_by도 함께 남는다
        coupon.delete(username);
    }

    /**
     * Redis의 총 수량을 바꾸고, DB가 롤백되면 되돌리도록 걸어 둔다.
     * 커밋 결과를 모르면(STATUS_UNKNOWN) 두 값 중 작은 쪽으로 둔다. DB가 큰 값으로 저장됐다면 덜 발급될 뿐이고,
     * 작은 값으로 저장됐다면 그대로 맞다. 큰 쪽으로 두면 DB 총 수량보다 더 발급될 수 있다.
     */
    private void changeTotal(Coupon coupon, int oldTotal, int newTotal) {
        Long couponId = coupon.getId();
        TotalChange change = issueGate.changeTotal(couponId, newTotal);
        if (change == TotalChange.NOT_LOADED) {
            // 채울 때는 DB에 아직 저장된 옛 총 수량으로 채운다(엔티티 값은 이미 새 값이다)
            issueState.ensureLoaded(couponId, oldTotal, coupon.getIssueEndAt());
            change = issueGate.changeTotal(couponId, newTotal);
        }
        if (change == TotalChange.BELOW_ISSUED) {
            throw new BusinessException(CouponErrorCode.COUPON_QUANTITY_BELOW_ISSUED);
        }
        if (change == TotalChange.NOT_LOADED) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        afterCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                issueGate.restoreTotal(couponId, newTotal, oldTotal);
            } else if (status == TransactionSynchronization.STATUS_UNKNOWN) {
                issueGate.restoreTotal(couponId, newTotal, Math.min(oldTotal, newTotal));
            }
        });
    }

    /** 트랜잭션이 끝난 뒤(커밋·롤백·결과 모름) 상태를 받아 실행한다. 되돌리기에 실패하면 맞추는 작업(CouponIssueReconcileService)이 바로잡는다. */
    private static void afterCompletion(IntConsumer action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                runQuietly(() -> action.accept(status));
            }
        });
    }

    /** 실패해도 요청은 성공으로 두고 기록만 남긴다. 어긋남은 다음 발급(정보가 없으면 DB에서 채움)이나 맞추는 작업이 바로잡는다. */
    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("쿠폰 발급 판단 정보(Redis)를 DB에 맞추지 못했습니다.", e);
        }
    }

    /**
     * 쿠폰 행을 잠그고 읽는다. 없거나 삭제된 쿠폰이면 404 COUPON_NOT_FOUND.
     * Note: 비관적 락(PESSIMISTIC_WRITE, SELECT ... FOR UPDATE)이다. 관리자 수정·중단·삭제끼리 차례로 처리한다.
     */
    private Coupon lockCoupon(Long couponId) {
        return couponRepository
                .lockById(couponId)
                .orElseThrow(() -> new BusinessException(CouponErrorCode.COUPON_NOT_FOUND));
    }

    /**
     * 현재 시각은 reservation-service 공용 Clock(ClockConfig, Asia/Seoul)으로 구한다. 서버 시간대와 상관없이 KST 기준이고, 테스트는 Clock을 바꿔 시각을 정한다.
     * Note: 초 단위로 자른다. 응답 시각 형식이 yyyy-MM-ddTHH:mm:ss이고, 발급 중단은 이 값을 종료 시각으로 저장한다.
     * 자르지 않으면 나노초가 응답에 붙고, PostgreSQL TIMESTAMP(마이크로초)에 저장한 값과 메모리의 값이 달라진다.
     */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
