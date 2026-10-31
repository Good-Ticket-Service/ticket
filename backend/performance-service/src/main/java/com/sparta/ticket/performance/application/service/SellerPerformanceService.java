package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.performance.application.dto.GradePriceSet;
import com.sparta.ticket.performance.application.dto.NewGradePrice;
import com.sparta.ticket.performance.application.dto.NewPerformance;
import com.sparta.ticket.performance.application.dto.PerformancePeriod;
import com.sparta.ticket.performance.application.dto.SellerPerformanceDetail;
import com.sparta.ticket.performance.application.port.PerformanceStore;
import com.sparta.ticket.performance.application.port.ScheduleStore;
import com.sparta.ticket.performance.application.port.VenueStore;
import com.sparta.ticket.performance.domain.performance.Performance;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import com.sparta.ticket.performance.domain.performance.PerformanceGradePrice;
import com.sparta.ticket.performance.domain.performance.PublicationRequirement;
import com.sparta.ticket.performance.domain.schedule.Schedule;
import com.sparta.ticket.performance.domain.venue.Venue;
import com.sparta.ticket.performance.domain.venue.VenueSeatGrade;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자 공연 명령: 공연 초안 등록, 등급별 가격 설정, 공연 공개. 명세는 .local/performance/api.md 5.3·5.4·5.6이다.
 * 가격 설정은 공연 행을 잠근 뒤 소유·If-Match·공개 여부를 확인하고, 성공하면 공연 version을 1 올린다.
 * Note: 가격 설정은 트랜잭션이 끝난 뒤 회차 좌석을 다시 준비해야 해서 @Transactional 대신 TransactionTemplate으로 범위를 정한다
 * (SellerScheduleService와 같은 이유).
 */
@Service
@RequiredArgsConstructor
public class SellerPerformanceService {

    private final PerformanceStore performanceStore;
    private final VenueStore venueStore;
    private final ScheduleStore scheduleStore;
    private final SeatSetupDispatcher seatSetupDispatcher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 공연 초안을 등록한다. 판매자는 인증 주체이며 요청 본문으로 받지 않는다.
     * Note: 공연장을 FOR SHARE로 잠근다. 같은 공연장의 배치 변경(FOR UPDATE)과 서로 기다리므로, 배치 변경이 사용 여부를 확인한 뒤
     * 이 공연이 공연장을 참조하는 일이 없다(이슈 #128 결정 3).
     */
    @Transactional
    public SellerPerformanceDetail registerDraft(Long sellerId, NewPerformance input) {
        Venue venue = venueStore
                .findByIdForShare(input.venueId())
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.VENUE_NOT_FOUND));
        Performance performance = performanceStore.save(Performance.createDraft(
                sellerId,
                venue.getId(),
                input.title(),
                input.genre(),
                input.ageRating(),
                input.runningTime(),
                input.castInfo(),
                input.description(),
                input.posterUrl()));
        // 새 공연은 회차가 없으므로 공연 기간이 없다
        return SellerPerformanceDetail.of(performance, venue, null);
    }

    /**
     * 공연의 등급별 가격 전체를 요청한 집합으로 바꾼다. 하나라도 실패하면 전체를 롤백한다.
     * Note: 확인 순서는 요청 안 등급 중복 400 → 404 → 403 → 409 VERSION_CONFLICT → 409 CONFIGURATION_LOCKED
     * → 400 VENUE_GRADE_MISMATCH → 400 GRADE_PRICE_MISSING이다. 요청 안 중복은 DB를 볼 필요 없는 형식 오류라 잠그기 전에 거절한다.
     * username은 논리 삭제한 가격 행의 deleted_by다. 생성·수정자는 JPA 감사가 채운다.
     * 좌석 구성이 바뀌므로 등록된 회차는 새 구성 버전으로 PENDING이 되고, 트랜잭션이 끝난 뒤 좌석을 다시 준비한다.
     */
    public GradePriceSet replaceGradePrices(
            Long sellerId, String username, Long performanceId, long expectedVersion, List<NewGradePrice> prices) {
        PricesReplaced replaced = transactionTemplate.execute(
                status -> replaceInTransaction(sellerId, username, performanceId, expectedVersion, prices));
        seatSetupDispatcher.dispatch(replaced.scheduleIds());
        return replaced.prices();
    }

    private PricesReplaced replaceInTransaction(
            Long sellerId, String username, Long performanceId, long expectedVersion, List<NewGradePrice> prices) {
        Set<Long> requestedGradeIds =
                prices.stream().map(NewGradePrice::venueSeatGradeId).collect(Collectors.toSet());
        if (requestedGradeIds.size() != prices.size()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "요청 안에 같은 등급이 있습니다.");
        }

        Performance performance = performanceStore
                .findByIdForUpdate(performanceId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.PERFORMANCE_NOT_FOUND));
        performance.verifyOwner(sellerId);
        performance.verifyVersion(expectedVersion);
        performance.verifyConfigurable();

        Map<Long, VenueSeatGrade> venueGrades = venueStore.findGrades(performance.getVenueId()).stream()
                .collect(Collectors.toMap(VenueSeatGrade::getId, Function.identity()));
        if (!venueGrades.keySet().containsAll(requestedGradeIds)) {
            throw new BusinessException(PerformanceErrorCode.VENUE_GRADE_MISMATCH);
        }
        if (!requestedGradeIds.containsAll(
                performanceStore.findSellableGradeIds(performanceId, performance.getVenueId()))) {
            throw new BusinessException(PerformanceErrorCode.GRADE_PRICE_MISSING);
        }

        replace(performanceId, prices, username);
        long version = performanceStore.increaseVersion(performanceId);
        // 좌석 구성이 바뀌었으므로 등록된 회차는 새 구성 버전으로 좌석을 다시 준비한다
        LocalDateTime now = LocalDateTime.now(clock);
        List<Schedule> schedules = scheduleStore.findByPerformanceIdForUpdate(performanceId);
        for (Schedule schedule : schedules) {
            schedule.requireSeatSetupForNewConfig(UUID.randomUUID().toString(), now);
            scheduleStore.saveSeatSetup(schedule);
        }

        GradePriceSet priceSet = new GradePriceSet(
                prices.stream()
                        .map(price -> new GradePriceSet.Item(
                                price.venueSeatGradeId(),
                                venueGrades.get(price.venueSeatGradeId()).getName(),
                                price.price()))
                        .toList(),
                version);
        return new PricesReplaced(
                priceSet, schedules.stream().map(Schedule::getId).toList());
    }

    /**
     * 초안 공연을 공개한다. 이미 공개된 공연이면 If-Match와 관계없이 현재 결과를 준다(응답 유실 뒤 재요청이 409가 되지 않게).
     * Note: 확인 순서는 404 → 403 → 409 INVALID_STATUS_TRANSITION(취소 공연) → 이미 공개면 200 → 409 VERSION_CONFLICT
     * → 409 PUBLICATION_REQUIREMENTS_NOT_MET이다. 공연 행을 FOR UPDATE로 잠그므로 회차 등록·가격 설정(같은 행 FOR UPDATE)과
     * 차례로 처리된다. 그래서 확인한 회차·가격이 공개 커밋 전에 바뀌지 않는다. 좌석 준비 상태(PENDING·FAILED)는 공개를 막지 않는다.
     */
    @Transactional
    public SellerPerformanceDetail publish(Long sellerId, Long performanceId, long expectedVersion) {
        Performance performance = performanceStore
                .findByIdForUpdate(performanceId)
                .orElseThrow(() -> new BusinessException(PerformanceErrorCode.PERFORMANCE_NOT_FOUND));
        performance.verifyOwner(sellerId);
        performance.verifyPublishable();

        PerformancePeriod period = scheduleStore.findActivePeriod(performanceId);
        if (!performance.isPublished()) {
            performance.verifyVersion(expectedVersion);
            verifyPublicationRequirements(performance, period);
            performance.publish(LocalDateTime.now(clock));
            performance = performanceStore.saveAndFlush(performance);
        }
        // 공연이 공연장을 참조하는 동안 공연장은 삭제되지 않는다(공연장 삭제 API 없음). 없으면 데이터 오류라 500으로 둔다
        Venue venue = venueStore
                .findByIdForShare(performance.getVenueId())
                .orElseThrow(() -> new IllegalStateException("공연의 공연장이 없습니다. performanceId=" + performanceId));
        return SellerPerformanceDetail.of(performance, venue, period);
    }

    /** 부족한 공개 필수 조건을 모두 모아 한 번에 알려 준다. 판매 대상 좌석이 없으면 가격 조건은 따지지 않는다. */
    private void verifyPublicationRequirements(Performance performance, PerformancePeriod period) {
        List<PublicationRequirement> missing = new ArrayList<>(performance.missingPublicationInfo());
        if (period == null) {
            missing.add(PublicationRequirement.SCHEDULES);
        }
        Set<Long> sellableGradeIds =
                performanceStore.findSellableGradeIds(performance.getId(), performance.getVenueId());
        if (sellableGradeIds.isEmpty()) {
            missing.add(PublicationRequirement.SELLABLE_SEATS);
        } else {
            Set<Long> pricedGradeIds = performanceStore.findGradePrices(performance.getId()).stream()
                    .map(PerformanceGradePrice::getVenueSeatGradeId)
                    .collect(Collectors.toSet());
            if (!pricedGradeIds.containsAll(sellableGradeIds)) {
                missing.add(PublicationRequirement.GRADE_PRICES);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(
                    PerformanceErrorCode.PUBLICATION_REQUIREMENTS_NOT_MET,
                    PerformanceErrorCode.PUBLICATION_REQUIREMENTS_NOT_MET.message() + " 부족한 항목: "
                            + missing.stream()
                                    .map(PublicationRequirement::getItem)
                                    .collect(Collectors.joining(", ")));
        }
    }

    /** 가격 설정 결과와, 트랜잭션이 끝난 뒤 좌석을 다시 준비할 회차 ID. */
    private record PricesReplaced(GradePriceSet prices, List<Long> scheduleIds) {}

    /**
     * 기존 가격 행과 요청을 등급 ID로 맞춰, 남는 등급은 가격만 바꾸고 빠진 등급은 논리 삭제하고 새 등급은 추가한다.
     * Note: 전부 지우고 다시 넣으면 Hibernate가 INSERT를 UPDATE(논리 삭제)보다 먼저 보내 부분 UNIQUE
     * uq_p_performance_grade_prices_grade에 걸린다. 그래서 등급별로 맞춘다.
     */
    private void replace(Long performanceId, List<NewGradePrice> prices, String username) {
        Map<Long, PerformanceGradePrice> existing = performanceStore.findGradePrices(performanceId).stream()
                .collect(Collectors.toMap(PerformanceGradePrice::getVenueSeatGradeId, Function.identity()));
        List<PerformanceGradePrice> added = new ArrayList<>();
        for (NewGradePrice price : prices) {
            PerformanceGradePrice current = existing.remove(price.venueSeatGradeId());
            if (current == null) {
                added.add(PerformanceGradePrice.create(performanceId, price.venueSeatGradeId(), price.price()));
            } else {
                current.changePrice(price.price());
            }
        }
        existing.values().forEach(removed -> removed.softDelete(username));
        performanceStore.saveGradePrices(added);
    }
}
