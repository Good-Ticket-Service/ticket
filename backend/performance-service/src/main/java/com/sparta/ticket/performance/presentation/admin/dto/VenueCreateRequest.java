package com.sparta.ticket.performance.presentation.admin.dto;

import com.sparta.ticket.performance.domain.venue.Region;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 공연장 등록 요청. 알 수 없는 필드는 무시한다(담당자 결정). */
public record VenueCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 255) String address,

        @NotNull @Schema(description = "시·도 코드 16개 중 하나. 한글 이름·임의 문자열은 받지 않는다", example = "SEOUL")
        Region region) {}
