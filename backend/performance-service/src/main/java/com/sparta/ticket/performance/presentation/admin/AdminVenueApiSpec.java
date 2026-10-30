package com.sparta.ticket.performance.presentation.admin;

import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.presentation.admin.dto.VenueCreateRequest;
import com.sparta.ticket.performance.presentation.admin.dto.VenueGradesCreateRequest;
import com.sparta.ticket.performance.presentation.admin.dto.VenueGradesResponse;
import com.sparta.ticket.performance.presentation.admin.dto.VenueResponse;
import com.sparta.ticket.performance.presentation.admin.dto.VenueSeatsCreateRequest;
import com.sparta.ticket.performance.presentation.admin.dto.VenueSeatsResponse;
import com.sparta.ticket.performance.presentation.support.IfMatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 관리자 공연장 배치 등록의 Swagger 문서와 파라미터 바인딩. 모두 MASTER 전용이다.
 * Note: If-Match는 required=false로 받는다. 그래야 헤더가 없을 때 Spring 대신 IfMatch.parse가 428을 낸다.
 */
@Tag(name = "관리자 공연장")
public interface AdminVenueApiSpec {

    @Operation(summary = "공연장 등록", description = "201과 Location: /api/seller/venues/{venueId}를 준다. version은 0에서 시작한다.")
    ResponseEntity<ApiResponse<VenueResponse>> register(@Valid @RequestBody VenueCreateRequest request);

    @Operation(
            summary = "공연장 좌석 등급 일괄 등록",
            description = "If-Match에 공연장 version을 넣는다(예: \"0\"). 없으면 428, 다르면 409 VERSION_CONFLICT. "
                    + "같은 공연장에 같은 이름이 있으면 409 DUPLICATE_VENUE_GRADE, 공연이 쓰는 공연장이면 409 VENUE_LAYOUT_IN_USE. "
                    + "성공하면 공연장 version이 1 오른다.")
    ApiResponse<VenueGradesResponse> registerGrades(
            @Parameter(description = "공연장 ID") @PathVariable Long venueId,
            @Parameter(description = "공연장 version. 예: \"0\"", required = true)
                    @RequestHeader(value = IfMatch.HEADER, required = false)
                    String ifMatch,
            @Valid @RequestBody VenueGradesCreateRequest request);

    @Operation(
            summary = "공연장 물리 좌석 일괄 등록",
            description = "If-Match는 등급 등록과 같다. 다른 공연장 등급이나 없는 등급은 400 VENUE_GRADE_MISMATCH, "
                    + "같은 구역·열·번호(요청 안 중복 포함)는 409 DUPLICATE_VENUE_SEAT이며 전체를 롤백한다.")
    ApiResponse<VenueSeatsResponse> registerSeats(
            @Parameter(description = "공연장 ID") @PathVariable Long venueId,
            @Parameter(description = "공연장 version. 예: \"1\"", required = true)
                    @RequestHeader(value = IfMatch.HEADER, required = false)
                    String ifMatch,
            @Valid @RequestBody VenueSeatsCreateRequest request);
}
