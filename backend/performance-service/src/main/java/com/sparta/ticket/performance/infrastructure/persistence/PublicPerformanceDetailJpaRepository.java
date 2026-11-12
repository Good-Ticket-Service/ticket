package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.performance.domain.performance.OperationStatus;
import com.sparta.ticket.performance.domain.performance.Performance;
import com.sparta.ticket.performance.domain.performance.PublicationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 공개 공연 상세의 고정 조회. 조건이 고정이라 QueryDSL 대신 JPQL 문자열로 둔다(이슈 #370 측정 후보 D2).
 * Note: QueryDSL은 요청마다 쿼리 객체를 조립·변환해, 같은 쿼리인데도 호출당 할당이 크게 늘었다(이슈 #370 저장소 비교).
 * 기간은 기본 정보와 함께 읽고(SQL 2개), 가격은 행 수가 달라 별도로 읽는다.
 */
interface PublicPerformanceDetailJpaRepository extends Repository<Performance, Long> {

    /** 공개·미삭제 공연과 그 공연장, 활성 회차(미삭제·ACTIVE)의 첫·마지막 시작 시각. 취소된 공연도 보여 준다. */
    @Query("select new com.sparta.ticket.performance.infrastructure.persistence.PublicPerformanceDetailRow("
            + " p.id, v.id, v.name, v.address, v.region, p.title, p.genre, p.ageRating, p.ageGuidance, p.runningTime,"
            + " p.castInfo, p.description, p.posterUrl, p.operationStatus, p.publishedAt, p.createdAt, p.updatedAt,"
            + " (select min(s1.startAt) from Schedule s1"
            + " where s1.performanceId = p.id and s1.deletedAt is null and s1.operationStatus = :active),"
            + " (select max(s2.startAt) from Schedule s2"
            + " where s2.performanceId = p.id and s2.deletedAt is null and s2.operationStatus = :active))"
            + " from Performance p join Venue v on v.id = p.venueId"
            + " where p.id = :id and p.publicationStatus = :published and p.deletedAt is null and v.deletedAt is null")
    Optional<PublicPerformanceDetailRow> findDetail(
            @Param("id") Long id,
            @Param("published") PublicationStatus published,
            @Param("active") OperationStatus active);

    /** 공연의 등급 가격. 삭제된 가격·등급은 뺀다. */
    @Query("select new com.sparta.ticket.performance.infrastructure.persistence.PublicPerformanceGradePriceRow("
            + " gp.venueSeatGradeId, g.name, gp.price)"
            + " from PerformanceGradePrice gp join VenueSeatGrade g on g.id = gp.venueSeatGradeId and g.deletedAt is null"
            + " where gp.performanceId = :id and gp.deletedAt is null order by gp.venueSeatGradeId asc")
    List<PublicPerformanceGradePriceRow> findGradePrices(@Param("id") Long id);
}
