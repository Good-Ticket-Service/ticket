package com.sparta.ticket.user.infrastructure.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class MasterAccountInitializerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private MasterAccountInitializer initializer(String username, String password) {
        return new MasterAccountInitializer(new MasterAccountProperties(username, password), userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("username이나 password가 비어 있으면 만들지 않는다")
    void skipsWhenBlank() {
        initializer("", "Master!2026").run(null);
        initializer("master", null).run(null);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("username 규칙(소문자·숫자 4~10자)에 맞지 않으면 만들지 않는다")
    void skipsWhenUsernameInvalid() {
        initializer("Master_Admin", "Master!2026").run(null);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("같은 username이 이미 있으면(탈퇴자 포함) 만들지 않는다")
    void skipsWhenExists() {
        given(userRepository.existsByUsernameIncludingDeleted("master")).willReturn(true);

        initializer("master", "Master!2026").run(null);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("없으면 BCrypt 해시와 MASTER 권한으로 만든다")
    void createsMaster() {
        given(passwordEncoder.encode("Master!2026")).willReturn("{hash}");

        initializer("master", "Master!2026").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("master");
        assertThat(saved.getValue().getPassword()).isEqualTo("{hash}");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.MASTER);
    }

    @Test
    @DisplayName("다른 인스턴스가 먼저 만들어 유니크 제약에 걸려도 기동을 막지 않는다")
    void ignoresConcurrentCreation() {
        given(passwordEncoder.encode(any())).willReturn("{hash}");
        given(userRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("duplicate"));

        assertThatCode(() -> initializer("master", "Master!2026").run(null)).doesNotThrowAnyException();
    }
}
