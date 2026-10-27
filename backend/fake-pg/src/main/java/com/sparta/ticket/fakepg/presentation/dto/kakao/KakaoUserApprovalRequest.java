package com.sparta.ticket.fakepg.presentation.dto.kakao;

import jakarta.validation.constraints.NotBlank;

/** 카카오 사용자 승인 시뮬레이션 요청. 결제창 URL을 연 사용자가 인증할 거래(tid)다. */
public record KakaoUserApprovalRequest(@NotBlank String tid) {}
