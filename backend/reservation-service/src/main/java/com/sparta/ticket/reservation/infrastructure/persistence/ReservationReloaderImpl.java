package com.sparta.ticket.reservation.infrastructure.persistence;

import com.sparta.ticket.reservation.domain.Reservation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;

/**
 * 조건부 UPDATE 뒤에 예약 하나만 DB에서 다시 읽는다.
 * Note: @Modifying(clearAutomatically = true)는 영속성 컨텍스트 전체를 비운다. 예약과 좌석은 한 트랜잭션에서 바뀌므로
 * seat 모듈이 잠가 둔 좌석 Entity까지 준영속이 되고, 그 뒤 seat가 바꾼 값은 오류 없이 버려진다. 그래서 전체를 비우지 않고
 * 예약만 refresh한다.
 */
public class ReservationReloaderImpl implements ReservationReloader {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<Reservation> reload(Long id) {
        Reservation reservation = entityManager.find(Reservation.class, id);
        if (reservation == null) {
            return Optional.empty();
        }
        entityManager.refresh(reservation);
        return Optional.of(reservation);
    }
}
