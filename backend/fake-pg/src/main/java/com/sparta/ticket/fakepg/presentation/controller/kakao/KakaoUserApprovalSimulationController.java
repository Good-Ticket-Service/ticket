package com.sparta.ticket.fakepg.presentation.controller.kakao;

import com.sparta.ticket.fakepg.application.service.kakao.KakaoPaymentService;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoUserApprovalRequest;
import com.sparta.ticket.fakepg.presentation.dto.kakao.KakaoUserApprovalResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카카오 사용자 승인 시뮬레이션. 실제 카카오에서는 클라이언트가 결제창 URL을 열고 사용자가 비밀번호로 인증하면,
 * 카카오가 approval_url로 리다이렉트하며 pg_token을 넘긴다. 프론트가 없으므로 테스트·k6가 이 API로 그 단계를 대신한다.
 * 실제 카카오에는 없는 fake PG 전용 API다.
 */
@RestController
@RequestMapping("/fake/kakao")
@RequiredArgsConstructor
public class KakaoUserApprovalSimulationController {

    private final KakaoPaymentService paymentService;

    @PostMapping("/approve")
    public KakaoUserApprovalResponse approve(@Valid @RequestBody KakaoUserApprovalRequest request) {
        return KakaoUserApprovalResponse.from(paymentService.issuePgToken(request.tid()));
    }
}
