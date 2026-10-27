package com.sparta.ticket.user.infrastructure.persistence;

import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserJpaRepository extends JpaRepository<User, Long>, UserRepository {

    /**
     * Note: User에는 @SQLRestriction("deleted_at IS NULL")이 붙어 있어서 JPQL과 메서드 이름 쿼리는 탈퇴자를 보지 못한다.
     * email은 탈퇴 후에도 재사용 불가라서 탈퇴자까지 확인해야 하므로, 제한이 적용되지 않는 네이티브 쿼리를 쓴다.
     */
    @Override
    @Query(value = "SELECT EXISTS (SELECT 1 FROM p_users WHERE email = :email)", nativeQuery = true)
    boolean existsByEmailIncludingDeleted(@Param("email") String email);
}
