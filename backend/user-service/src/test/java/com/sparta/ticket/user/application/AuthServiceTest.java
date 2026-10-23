package com.sparta.ticket.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.user.UserFixture;
import com.sparta.ticket.user.application.dto.LoginCommand;
import com.sparta.ticket.user.application.dto.SignupCommand;
import com.sparta.ticket.user.application.dto.SignupResult;
import com.sparta.ticket.user.application.dto.TokenResult;
import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserErrorCode;
import com.sparta.ticket.user.domain.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenIssuer tokenIssuer;

    private AuthService authService;

    private final SignupCommand signup = new SignupCommand("hong01", "Sparta!2026", "홍길동");

    @BeforeEach
    void setUp() {
        given(passwordEncoder.encode(any())).willReturn("dummy-hash");
        authService = new AuthService(userRepository, passwordEncoder, tokenIssuer);
    }

    @Test
    @DisplayName("회원가입하면 비밀번호를 해시해 USER 권한으로 저장한다")
    void signup() {
        given(userRepository.existsByUsernameIncludingDeleted("hong01")).willReturn(false);
        given(passwordEncoder.encode("Sparta!2026")).willReturn("hashed");
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

        SignupResult result = authService.signup(signup);

        assertThat(result.username()).isEqualTo("hong01");
        assertThat(result.role()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("탈퇴자를 포함해 이미 쓰인 username이면 DUPLICATE_USERNAME")
    void duplicateUsername() {
        given(userRepository.existsByUsernameIncludingDeleted("hong01")).willReturn(true);

        assertThatThrownBy(() -> authService.signup(signup))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.DUPLICATE_USERNAME);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("저장 시점에 유니크 제약이 위반되면(동시 가입) DUPLICATE_USERNAME")
    void uniqueViolationOnSave() {
        given(userRepository.existsByUsernameIncludingDeleted("hong01")).willReturn(false);
        given(passwordEncoder.encode("Sparta!2026")).willReturn("hashed");
        given(userRepository.saveAndFlush(any(User.class)))
                .willThrow(new DataIntegrityViolationException("uk_username"));

        assertThatThrownBy(() -> authService.signup(signup))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.DUPLICATE_USERNAME);
    }

    @Test
    @DisplayName("비밀번호가 맞으면 Bearer 토큰을 발급한다")
    void login() {
        User user = UserFixture.user("hong01", "hashed");
        given(userRepository.findByUsername("hong01")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("Sparta!2026", "hashed")).willReturn(true);
        given(tokenIssuer.issue(user)).willReturn("token");
        given(tokenIssuer.expirationSeconds()).willReturn(3600L);

        TokenResult result = authService.login(new LoginCommand("hong01", "Sparta!2026"));

        assertThat(result).isEqualTo(new TokenResult("token", "Bearer", 3600));
    }

    @Test
    @DisplayName("비밀번호가 틀리거나 사용자가 없으면 같은 LOGIN_FAILED")
    void loginFailed() {
        User user = UserFixture.user("hong01", "hashed");
        given(userRepository.findByUsername("hong01")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrong", "hashed")).willReturn(false);
        given(userRepository.findByUsername("nobody")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginCommand("hong01", "wrong")))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.LOGIN_FAILED);
        assertThatThrownBy(() -> authService.login(new LoginCommand("nobody", "Sparta!2026")))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("없는 사용자도 더미 해시와 비교해 BCrypt 비용을 똑같이 쓴다")
    void comparesDummyHashForMissingUser() {
        given(userRepository.findByUsername("nobody")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginCommand("nobody", "Sparta!2026")))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.LOGIN_FAILED);
        verify(passwordEncoder).matches("Sparta!2026", "dummy-hash");
    }
}
