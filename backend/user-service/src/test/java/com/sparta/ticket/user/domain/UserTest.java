package com.sparta.ticket.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserTest {

    private User user() {
        return User.signup("fan@ticket.com", "{hash}", "홍길동", "010-1234-5678");
    }

    @Test
    @DisplayName("이름과 전화번호를 함께 바꾼다")
    void updatesBoth() {
        User user = user();

        user.updateProfile("김태현", "010-9876-5432");

        assertThat(user.getName()).isEqualTo("김태현");
        assertThat(user.getPhone()).isEqualTo("010-9876-5432");
    }

    @Test
    @DisplayName("null로 받은 값은 바꾸지 않는다(이름만 수정)")
    void keepsPhoneWhenNull() {
        User user = user();

        user.updateProfile("김태현", null);

        assertThat(user.getName()).isEqualTo("김태현");
        assertThat(user.getPhone()).isEqualTo("010-1234-5678");
    }

    @Test
    @DisplayName("null로 받은 값은 바꾸지 않는다(전화번호만 수정)")
    void keepsNameWhenNull() {
        User user = user();

        user.updateProfile(null, "010-9876-5432");

        assertThat(user.getName()).isEqualTo("홍길동");
        assertThat(user.getPhone()).isEqualTo("010-9876-5432");
    }

    @Test
    @DisplayName("둘 다 null이면 아무것도 바뀌지 않는다")
    void keepsAllWhenBothNull() {
        User user = user();

        user.updateProfile(null, null);

        assertThat(user.getName()).isEqualTo("홍길동");
        assertThat(user.getPhone()).isEqualTo("010-1234-5678");
    }
}
