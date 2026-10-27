package com.sparta.ticket.user;

import com.sparta.ticket.user.domain.User;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.util.ReflectionTestUtils;

public final class UserFixture {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private UserFixture() {}

    public static User user(String email, String encodedPassword) {
        User user = User.signup(email, encodedPassword, "홍길동", "010-1234-5678");
        ReflectionTestUtils.setField(user, "id", SEQUENCE.incrementAndGet());
        return user;
    }
}
