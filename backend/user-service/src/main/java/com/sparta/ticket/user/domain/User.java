package com.sparta.ticket.user.domain;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Entity
@Table(name = "p_users")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id")
    private UUID id;

    /** 탈퇴 후에도 재사용할 수 없어서 부분 인덱스가 아닌 일반 유니크 제약을 둔다. */
    @Column(nullable = false, unique = true, length = 10)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    private User(String username, String encodedPassword, String name, Role role) {
        this.username = username;
        this.password = encodedPassword;
        this.name = name;
        this.role = role;
    }

    /** 가입 즉시 활성화되며 권한은 USER로 시작한다. */
    public static User signup(String username, String encodedPassword, String name) {
        return new User(username, encodedPassword, name, Role.USER);
    }

    /** 기동 시 환경 변수로 만드는 초기 관리자. 가입 API로는 MASTER를 만들 수 없으므로 이 경로만 MASTER로 시작한다. */
    public static User master(String username, String encodedPassword) {
        return new User(username, encodedPassword, "마스터", Role.MASTER);
    }
}
