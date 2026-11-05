package com.sparta.ticket.fakepg.application.service.approval;

import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoPaymentResponse;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoReadyResponse;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoUserApprovalResponse;
import com.sparta.ticket.fakepg.presentation.dto.toss.CheckoutSimulationResponse;
import com.sparta.ticket.fakepg.presentation.dto.toss.TossPaymentResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Opt-in load-test mode: deterministic successful approvals without Fake PG transaction writes. */
@Component
public class InMemoryApprovalMode {

    private final boolean enabled;
    private final ConcurrentMap<String, Approval> approvals = new ConcurrentHashMap<>();

    private record Approval(String orderId, String partnerUserId, String itemName, int amount) {}

    public InMemoryApprovalMode(@Value("${FAKE_PG_APPROVAL_IN_MEMORY:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public CheckoutSimulationResponse tossCheckout(String orderId, int amount) {
        approvals.put(tossKey(orderId), new Approval(orderId, "", "P13 load test", amount));
        LocalDateTime now = LocalDateTime.now();
        return new CheckoutSimulationResponse(tossKey(orderId), orderId, amount, now.plusMinutes(10));
    }

    public TossPaymentResponse tossApprove(String paymentKey, String orderId, int amount) {
        Approval approval = approvals.get(paymentKey);
        if (approval == null || !approval.orderId().equals(orderId) || approval.amount() != amount) {
            throw new IllegalArgumentException("Unknown in-memory Toss payment key");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(9));
        return new TossPaymentResponse(
                "tosspayments",
                "2022-11-16",
                paymentKey,
                "NORMAL",
                orderId,
                "P13 load test",
                "KRW",
                "카드",
                amount,
                amount,
                "DONE",
                now,
                now,
                null);
    }

    public KakaoUserApprovalResponse kakaoUserApproval(String tid) {
        Approval approval = approvals.get(tid);
        if (approval == null) {
            throw new IllegalArgumentException("Unknown in-memory Kakao tid");
        }
        return new KakaoUserApprovalResponse(tid, approval.orderId(), kakaoToken(tid));
    }

    public KakaoPaymentResponse kakaoApprove(
            String cid, String tid, String orderId, String partnerUserId, String pgToken) {
        Approval approval = approvals.get(tid);
        if (approval == null
                || !approval.orderId().equals(orderId)
                || !approval.partnerUserId().equals(partnerUserId)
                || !kakaoToken(tid).equals(pgToken)) {
            throw new IllegalArgumentException("Unknown in-memory Kakao approval");
        }
        int amount = approval.amount();
        LocalDateTime now = LocalDateTime.now();
        var total = new KakaoPaymentResponse.Amount(amount, 0, amount / 11, 0, 0);
        var zero = new KakaoPaymentResponse.Amount(0, 0, 0, 0, 0);
        return new KakaoPaymentResponse(
                "A" + token(orderId),
                tid,
                cid,
                "SUCCESS_PAYMENT",
                orderId,
                partnerUserId,
                "MONEY",
                total,
                zero,
                total,
                approval.itemName(),
                1,
                now,
                now,
                null);
    }

    private static String tossKey(String orderId) {
        return "p13_toss_" + token(orderId);
    }

    private static String kakaoTid(String orderId) {
        return "p13_kakao_" + token(orderId);
    }

    private static String kakaoToken(String tid) {
        return "p13_token_" + token(tid);
    }

    public KakaoReadyResponse kakaoReady(
            String orderId, String partnerUserId, String itemName, int amount, String checkoutUrlBase) {
        String tid = kakaoTid(orderId);
        approvals.put(tid, new Approval(orderId, partnerUserId, itemName, amount));
        String url = checkoutUrlBase + "/fake/kakao/checkout/" + tid;
        return new KakaoReadyResponse(tid, url, url, url, url, url, LocalDateTime.now());
    }

    private static String token(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
