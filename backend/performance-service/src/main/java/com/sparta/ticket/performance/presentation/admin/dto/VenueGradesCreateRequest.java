package com.sparta.ticket.performance.presentation.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 좌석 등급 일괄 등록 요청. 요청 안 이름 중복(400)은 공백을 지운 뒤 서비스에서 본다.
 * Note: 일괄 개수 상한은 아직 없다. 나중에 정한다(.local/performance/plan.md 6절).
 */
public record VenueGradesCreateRequest(@NotEmpty List<@NotNull @Valid Grade> grades) {

    public record Grade(@NotBlank @Size(max = 20) String name) {}
}
