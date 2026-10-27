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
import com.sparta.ticket.user.domain.UserStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenIssuer tokenIssuer;

    private AuthService authService;

    private final SignupCommand signup = new SignupCommand("hong@ticket.com", "Sparta!2026", "홍길동", "010-1234-5678");

    @BeforeEach
    void setUp() {
        given(passwordEncoder.encode(any())).willReturn("dummy-hash");
        authService = new AuthService(userRepository, passwordEncoder, tokenIssuer);
    }

    @Test
    @DisplayName("회원가입하면 비밀번호를 해시해 USER 권한으로 저장한다")
    void signup() {
        given(userRepository.existsByEmailIncludingDeleted("hong@ticket.com")).willReturn(false);
        given(passwordEncoder.encode("Sparta!2026")).willReturn("hashed");
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

        SignupResult result = authService.signup(signup);

        assertThat(result.email()).isEqualTo("hong@ticket.com");
        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.role()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("탈퇴자를 포함해 이미 쓰인 email이면 DUPLICATE_EMAIL")
    void duplicateEmail() {
        given(userRepository.existsByEmailIncludingDeleted("hong@ticket.com")).willReturn(true);

        assertThatThrownBy(() -> authService.signup(signup))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.DUPLICATE_EMAIL);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("저장 시점에 유니크 제약이 위반되면(동시 가입) DUPLICATE_EMAIL")
    void uniqueViolationOnSave() {
        given(userRepository.existsByEmailIncludingDeleted("hong@ticket.com")).willReturn(false);
        given(passwordEncoder.encode("Sparta!2026")).willReturn("hashed");
        given(userRepository.saveAndFlush(any(User.class)))
                .willThrow(new DataIntegrityViolationException("uk_p_users_email"));

        assertThatThrownBy(() -> authService.signup(signup))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    @DisplayName("비밀번호가 맞으면 Bearer 토큰을 발급한다")
    void login() {
        User user = UserFixture.user("hong@ticket.com", "hashed");
        given(userRepository.findByEmail("hong@ticket.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("Sparta!2026", "hashed")).willReturn(true);
        given(tokenIssuer.issue(user)).willReturn("token");
        given(tokenIssuer.expirationSeconds()).willReturn(3600L);

        TokenResult result = authService.login(new LoginCommand("hong@ticket.com", "Sparta!2026"));

        assertThat(result).isEqualTo(new TokenResult("token", "Bearer", 3600));
    }

    @Test
    @DisplayName("비밀번호가 틀리거나 사용자가 없으면 같은 LOGIN_FAILED")
    void loginFailed() {
        User user = UserFixture.user("hong@ticket.com", "hashed");
        given(userRepository.findByEmail("hong@ticket.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrong", "hashed")).willReturn(false);
        given(userRepository.findByEmail("nobody@ticket.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginCommand("hong@ticket.com", "wrong")))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.LOGIN_FAILED);
        assertThatThrownBy(() -> authService.login(new LoginCommand("nobody@ticket.com", "Sparta!2026")))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("없는 사용자도 더미 해시와 비교해 BCrypt 비용을 똑같이 쓴다")
    void comparesDummyHashForMissingUser() {
        given(userRepository.findByEmail("nobody@ticket.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginCommand("nobody@ticket.com", "Sparta!2026")))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.LOGIN_FAILED);
        verify(passwordEncoder).matches("Sparta!2026", "dummy-hash");
    }

    @Test
    @DisplayName("탈퇴(WITHDRAWN) 상태면 비밀번호가 맞아도 LOGIN_FAILED")
    void withdrawnUserCannotLogin() {
        User user = UserFixture.user("hong@ticket.com", "hashed");
        ReflectionTestUtils.setField(user, "status", UserStatus.WITHDRAWN);
        given(userRepository.findByEmail("hong@ticket.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("Sparta!2026", "hashed")).willReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginCommand("hong@ticket.com", "Sparta!2026")))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.LOGIN_FAILED);
    }
}
