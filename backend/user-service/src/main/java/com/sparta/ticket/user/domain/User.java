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
import java.time.LocalDateTime;
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

    /** 초기 MASTER는 가입 절차를 거치지 않아 전화번호가 없다. NOT NULL 컬럼을 채우는 자리표시 값이다. */
    private static final String MASTER_PHONE = "000-0000-0000";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    /** 로그인 ID. 탈퇴 후에도 재사용할 수 없어서 부분 인덱스가 아닌 일반 유니크 제약을 둔다. */
    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    /** 탈퇴 시각. 1년 뒤 개인정보 파기의 기준이다. */
    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    private User(String email, String encodedPassword, String name, String phone, Role role) {
        this.email = email;
        this.password = encodedPassword;
        this.name = name;
        this.phone = phone;
        this.role = role;
        this.status = UserStatus.ACTIVE;
    }

    /** 일반 회원가입. 가입 즉시 활성화되며 권한은 USER로 시작한다. */
    public static User signup(String email, String encodedPassword, String name, String phone) {
        return new User(email, encodedPassword, name, phone, Role.USER);
    }

    /** 판매자 회원가입. 승인 절차 없이 가입 즉시 SELLER로 활성화된다. */
    public static User sellerSignup(String email, String encodedPassword, String name, String phone) {
        return new User(email, encodedPassword, name, phone, Role.SELLER);
    }

    /** 기동 시 환경 변수로 만드는 초기 관리자. 가입 API로는 MASTER를 만들 수 없으므로 이 경로만 MASTER로 시작한다. */
    public static User master(String email, String encodedPassword) {
        return new User(email, encodedPassword, "마스터", MASTER_PHONE, Role.MASTER);
    }

    /** 내 정보 부분 수정. null로 받은 값은 바꾸지 않고 기존 값을 유지한다. 형식 검증은 요청 DTO가 맡는다. */
    public void updateProfile(String name, String phone) {
        if (name != null) {
            this.name = name;
        }
        if (phone != null) {
            this.phone = phone;
        }
    }

    /** 탈퇴(WITHDRAWN) 회원은 로그인할 수 없다. */
    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
