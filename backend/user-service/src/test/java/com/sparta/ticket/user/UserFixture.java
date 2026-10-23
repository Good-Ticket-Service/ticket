package com.sparta.ticket.user;

import com.sparta.ticket.user.domain.User;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

public final class UserFixture {

    private UserFixture() {
    }

    public static User user(String username, String encodedPassword) {
        User user = User.signup(username, encodedPassword, "홍길동");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
