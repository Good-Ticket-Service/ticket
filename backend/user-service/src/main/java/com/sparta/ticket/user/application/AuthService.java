package com.sparta.ticket.user.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.user.application.dto.LoginCommand;
import com.sparta.ticket.user.application.dto.SignupCommand;
import com.sparta.ticket.user.application.dto.SignupResult;
import com.sparta.ticket.user.application.dto.TokenResult;
import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserErrorCode;
import com.sparta.ticket.user.domain.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    /** 없는 사용자로 로그인할 때도 같은 비용의 BCrypt 비교를 하기 위한 값. */
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenIssuer tokenIssuer) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public SignupResult signup(SignupCommand command) {
        if (userRepository.existsByEmailIncludingDeleted(command.email())) {
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }
        User user = User.signup(
                command.email(), passwordEncoder.encode(command.password()), command.name(), command.phone());
        try {
            return SignupResult.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // Note: 존재 확인과 저장 사이에 같은 email 가입이 끼어들 수 있다. 최종 방어선은 DB 유니크 제약이고, 그 위반을 같은 409로 바꾼다.
            // save()만 하면 INSERT가 커밋 시점까지 미뤄져 예외가 이 try 밖(트랜잭션 프록시)에서 터진다. saveAndFlush로 여기서 INSERT를 실행한다.
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }
    }

    /**
     * 없는 사용자, 탈퇴 사용자, 비밀번호 불일치를 모두 같은 LOGIN_FAILED로 응답해 계정 존재 여부를 노출하지 않는다.
     * Note: 응답 문구만 같아서는 부족하다. 없는 사용자일 때 BCrypt 비교를 건너뛰면 응답이 눈에 띄게 빨라져 시간으로 계정 존재를 알 수 있다.
     * 그래서 사용자가 없어도 더미 해시와 비교해 같은 비용을 쓴다.
     */
    @Transactional(readOnly = true)
    public TokenResult login(LoginCommand command) {
        Optional<User> found = userRepository.findByEmail(command.email());
        String hash = found.map(User::getPassword).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(command.password(), hash);
        User user = found.filter(candidate -> matches && candidate.isActive())
                .orElseThrow(() -> new BusinessException(UserErrorCode.LOGIN_FAILED));
        return TokenResult.bearer(tokenIssuer.issue(user), tokenIssuer.expirationSeconds());
    }
}
