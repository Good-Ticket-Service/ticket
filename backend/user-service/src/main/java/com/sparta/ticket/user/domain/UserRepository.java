package com.sparta.ticket.user.domain;

import java.util.Optional;

/**
 * domain이 필요로 하는 저장소 기능만 선언한다. 구현은 infrastructure의 Spring Data 인터페이스가 맡는다.
 */
public interface UserRepository {

    /** 저장하고 바로 INSERT를 실행한다. 유니크 제약 위반을 호출한 메서드 안에서 잡기 위해 쓴다. */
    User saveAndFlush(User user);

    Optional<User> findById(Long id);

    Optional<User> findByEmail(String email);

    /** 탈퇴(논리 삭제)한 사용자까지 포함해 email 사용 여부를 확인한다. */
    boolean existsByEmailIncludingDeleted(String email);
}
