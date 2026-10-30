package com.sparta.ticket.performance.infrastructure.persistence;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.performance.application.port.VenueStore;
import com.sparta.ticket.performance.domain.performance.PerformanceErrorCode;
import com.sparta.ticket.performance.domain.venue.SeatPosition;
import com.sparta.ticket.performance.domain.venue.Venue;
import com.sparta.ticket.performance.domain.venue.VenueSeat;
import com.sparta.ticket.performance.domain.venue.VenueSeatGrade;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * VenueStore의 구현. 저장·조회는 Spring Data에 맡기고, 부분 UNIQUE 위반만 업무 오류(409)로 바꾼다.
 * version 증가는 Spring Data로 표현할 수 없어 EntityManager로 한다.
 */
@Repository
@RequiredArgsConstructor
public class VenueStoreAdapter implements VenueStore {

    static final String GRADE_NAME_UNIQUE = "uq_p_venue_seat_grades_venue_name";
    static final String SEAT_POSITION_UNIQUE = "uq_p_venue_seats_position";

    private final VenueJpaRepository venueRepository;
    private final VenueSeatGradeJpaRepository gradeRepository;
    private final VenueSeatJpaRepository seatRepository;
    private final EntityManager entityManager;

    @Override
    public Venue save(Venue venue) {
        return venueRepository.save(venue);
    }

    @Override
    public Optional<Venue> findByIdForUpdate(Long venueId) {
        return venueRepository.findByIdForUpdate(venueId);
    }

    @Override
    public Optional<Venue> findByIdForShare(Long venueId) {
        return venueRepository.findByIdForShare(venueId);
    }

    @Override
    public boolean isUsedByPerformance(Long venueId) {
        return venueRepository.isUsedByPerformance(venueId);
    }

    @Override
    public Set<String> findGradeNames(Long venueId) {
        return new HashSet<>(gradeRepository.findNamesByVenueId(venueId));
    }

    @Override
    public List<VenueSeatGrade> findGrades(Long venueId) {
        return gradeRepository.findByVenueId(venueId);
    }

    @Override
    public Set<Long> findGradeIds(Long venueId) {
        return new HashSet<>(gradeRepository.findIdsByVenueId(venueId));
    }

    @Override
    public Set<SeatPosition> findSeatPositions(Long venueId, Collection<String> sections) {
        // 빈 목록으로 IN 쿼리를 만들지 않는다
        if (sections.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(seatRepository.findPositions(venueId, sections));
    }

    @Override
    public List<VenueSeatGrade> saveGrades(List<VenueSeatGrade> grades) {
        return saveOrConflict(
                () -> gradeRepository.saveAllAndFlush(grades),
                GRADE_NAME_UNIQUE,
                PerformanceErrorCode.DUPLICATE_VENUE_GRADE);
    }

    @Override
    public List<VenueSeat> saveSeats(List<VenueSeat> seats) {
        return saveOrConflict(
                () -> seatRepository.saveAllAndFlush(seats),
                SEAT_POSITION_UNIQUE,
                PerformanceErrorCode.DUPLICATE_VENUE_SEAT);
    }

    /**
     * Note: 이미 PESSIMISTIC_WRITE로 읽은 공연장을 PESSIMISTIC_FORCE_INCREMENT로 다시 잠그면 version UPDATE가 바로 나가고
     * 엔티티의 version도 오른다. OPTIMISTIC_FORCE_INCREMENT는 커밋 때 올라 응답에 넣을 값을 미리 읽을 수 없다.
     * 잠금으로 읽은 공연장이 영속성 컨텍스트에 있으므로 find는 DB를 다시 읽지 않는다.
     */
    @Override
    public long increaseVersion(Long venueId) {
        Venue venue = entityManager.find(Venue.class, venueId);
        entityManager.lock(venue, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        return venue.getVersion();
    }

    /**
     * 공연장 잠금으로 같은 공연장의 동시 삽입은 막히지만, 안전망으로 부분 UNIQUE 위반을 409로 바꾼다.
     * 다른 제약 위반은 예상하지 못한 오류라 그대로 던진다(500).
     */
    private static <T> List<T> saveOrConflict(
            Supplier<List<T>> save, String uniqueIndex, PerformanceErrorCode duplicateCode) {
        try {
            return save.get();
        } catch (DataIntegrityViolationException e) {
            if (violates(e, uniqueIndex)) {
                throw new BusinessException(duplicateCode);
            }
            throw e;
        }
    }

    /**
     * 원인 중 PostgreSQL 드라이버 예외를 찾아, 서버가 오류 정보에 따로 담아 보낸 제약 이름을 비교한다.
     * Note: Hibernate의 ConstraintViolationException.getConstraintName()은 이 이름을 영문 오류 메시지 문장에서 잘라 낸다.
     * DB 메시지 언어(lc_messages)가 영어가 아니면 null이 되어 409가 아니라 500이 나간다. 서버 오류 정보의 제약 이름은 언어와 관계없다.
     */
    private static boolean violates(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return constraintName.equalsIgnoreCase(
                        psql.getServerErrorMessage().getConstraint());
            }
        }
        return false;
    }
}
