package com.sparta.ticket.performance.presentation.seller;

import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.performance.application.dto.NewGradePrice;
import com.sparta.ticket.performance.application.dto.NewPerformance;
import com.sparta.ticket.performance.application.dto.SellerPerformanceDetail;
import com.sparta.ticket.performance.application.service.SellerPerformanceService;
import com.sparta.ticket.performance.domain.performance.AgeRating;
import com.sparta.ticket.performance.domain.performance.Genre;
import com.sparta.ticket.performance.presentation.seller.dto.GradePricesResponse;
import com.sparta.ticket.performance.presentation.seller.dto.GradePricesUpdateRequest;
import com.sparta.ticket.performance.presentation.seller.dto.PerformanceCreateRequest;
import com.sparta.ticket.performance.presentation.seller.dto.SellerPerformanceResponse;
import com.sparta.ticket.performance.presentation.support.IfMatch;
import com.sparta.ticket.performance.presentation.support.RequestText;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 판매자 공연 명령 API. 바인딩과 Swagger 문서는 ApiSpec에 둔다. 앞뒤 공백은 여기서 지운다. */
@RestController
@RequiredArgsConstructor
@RequireRole(Role.SELLER)
@RequestMapping("/api/seller/performances")
public class SellerPerformanceApi implements SellerPerformanceApiSpec {

    private static final String SUCCESS = "SUCCESS";
    private static final String PERFORMANCE_LOCATION = "/api/seller/performances/";

    private final SellerPerformanceService service;

    @Override
    @PostMapping
    public ResponseEntity<ApiResponse<SellerPerformanceResponse>> registerDraft(
            UserContext user, PerformanceCreateRequest request) {
        SellerPerformanceDetail performance = service.registerDraft(
                user.userId(),
                new NewPerformance(
                        request.venueId(),
                        RequestText.strip(request.title()),
                        Genre.valueOf(request.genre()),
                        AgeRating.fromCode(request.ageRating()),
                        request.runningTime(),
                        RequestText.stripToNull(request.castInfo()),
                        RequestText.stripToNull(request.description()),
                        RequestText.stripToNull(request.posterUrl())));
        return ResponseEntity.created(URI.create(PERFORMANCE_LOCATION + performance.id()))
                .body(ApiResponse.created(SUCCESS, SellerPerformanceResponse.from(performance)));
    }

    @Override
    @PutMapping("/{performanceId}/grade-prices")
    public ApiResponse<GradePricesResponse> replaceGradePrices(
            UserContext user, Long performanceId, String ifMatch, GradePricesUpdateRequest request) {
        long expectedVersion = IfMatch.parse(ifMatch);
        List<NewGradePrice> prices = request.gradePrices().stream()
                .map(price -> new NewGradePrice(price.venueSeatGradeId(), price.price()))
                .toList();
        return ApiResponse.ok(
                SUCCESS,
                GradePricesResponse.from(service.replaceGradePrices(
                        user.userId(), user.username(), performanceId, expectedVersion, prices)));
    }

    @Override
    @PostMapping("/{performanceId}/publication")
    public ApiResponse<SellerPerformanceResponse> publish(UserContext user, Long performanceId, String ifMatch) {
        long expectedVersion = IfMatch.parse(ifMatch);
        return ApiResponse.ok(
                SUCCESS,
                SellerPerformanceResponse.from(service.publish(user.userId(), performanceId, expectedVersion)));
    }
}
