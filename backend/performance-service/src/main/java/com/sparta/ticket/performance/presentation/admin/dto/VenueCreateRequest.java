package com.sparta.ticket.performance.presentation.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 공연장 등록 요청. 알 수 없는 필드는 무시한다(담당자 결정). */
public record VenueCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 255) String address,
        @NotBlank @Size(max = 20) String region) {}
