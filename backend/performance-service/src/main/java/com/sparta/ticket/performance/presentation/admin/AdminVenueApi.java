package com.sparta.ticket.performance.presentation.admin;

import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.application.dto.NewVenueSeat;
import com.sparta.ticket.performance.application.dto.VenueDetail;
import com.sparta.ticket.performance.application.service.VenueAdminService;
import com.sparta.ticket.performance.presentation.admin.dto.VenueCreateRequest;
import com.sparta.ticket.performance.presentation.admin.dto.VenueGradesCreateRequest;
import com.sparta.ticket.performance.presentation.admin.dto.VenueGradesResponse;
import com.sparta.ticket.performance.presentation.admin.dto.VenueResponse;
import com.sparta.ticket.performance.presentation.admin.dto.VenueSeatsCreateRequest;
import com.sparta.ticket.performance.presentation.admin.dto.VenueSeatsResponse;
import com.sparta.ticket.performance.presentation.support.IfMatch;
import com.sparta.ticket.performance.presentation.support.RequestText;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 공연장 배치 등록 API. 바인딩과 Swagger 문서는 ApiSpec에 둔다. 앞뒤 공백은 여기서 지운다(결정 5). */
@RestController
@RequiredArgsConstructor
@RequireRole(Role.MASTER)
@RequestMapping("/api/admin/venues")
public class AdminVenueApi implements AdminVenueApiSpec {

    private static final String SUCCESS = "SUCCESS";
    private static final String VENUE_LOCATION = "/api/seller/venues/";

    private final VenueAdminService service;

    @Override
    @PostMapping
    public ResponseEntity<ApiResponse<VenueResponse>> register(VenueCreateRequest request) {
        VenueDetail venue = service.register(
                RequestText.strip(request.name()),
                RequestText.strip(request.address()),
                RequestText.strip(request.region()));
        return ResponseEntity.created(URI.create(VENUE_LOCATION + venue.id()))
                .body(ApiResponse.created(SUCCESS, VenueResponse.from(venue)));
    }

    @Override
    @PostMapping("/{venueId}/seat-grades")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VenueGradesResponse> registerGrades(
            Long venueId, String ifMatch, VenueGradesCreateRequest request) {
        long expectedVersion = IfMatch.parse(ifMatch);
        List<String> names = request.grades().stream()
                .map(grade -> RequestText.strip(grade.name()))
                .toList();
        return ApiResponse.created(
                SUCCESS, VenueGradesResponse.from(service.registerGrades(venueId, expectedVersion, names)));
    }

    @Override
    @PostMapping("/{venueId}/seats")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VenueSeatsResponse> registerSeats(
            Long venueId, String ifMatch, VenueSeatsCreateRequest request) {
        long expectedVersion = IfMatch.parse(ifMatch);
        List<NewVenueSeat> seats = request.seats().stream()
                .map(seat -> new NewVenueSeat(
                        RequestText.strip(seat.section()),
                        RequestText.strip(seat.rowLabel()),
                        seat.seatNumber(),
                        seat.venueSeatGradeId()))
                .toList();
        return ApiResponse.created(
                SUCCESS, VenueSeatsResponse.from(service.registerSeats(venueId, expectedVersion, seats)));
    }
}
