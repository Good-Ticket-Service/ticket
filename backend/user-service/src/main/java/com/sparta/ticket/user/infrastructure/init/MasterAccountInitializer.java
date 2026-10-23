package com.sparta.ticket.user.infrastructure.init;

import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserRepository;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 회원가입은 항상 USER로 만들어지므로, 첫 MASTER는 기동할 때 환경 변수(MASTER_USERNAME, MASTER_PASSWORD)로 만든다.
 * 값이 비어 있거나 같은 username이 이미 있으면(탈퇴자 포함) 아무것도 하지 않는다. 기존 계정의 권한은 바꾸지 않는다.
 *
 * Note: 메서드에 @Transactional을 걸지 않는다. 걸면 저장 실패 시 트랜잭션이 rollback-only로 표시돼, 예외를 잡아도
 * 메서드가 끝날 때 커밋하면서 UnexpectedRollbackException이 난다. saveAndFlush가 자기 트랜잭션에서 INSERT까지 실행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(MasterAccountProperties.class)
public class MasterAccountInitializer implements ApplicationRunner {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9]{4,10}$");

    private final MasterAccountProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        String username = properties.username();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(properties.password())) {
            log.info("MASTER_USERNAME 또는 MASTER_PASSWORD가 비어 있어 MASTER 계정 생성을 건너뛴다.");
            return;
        }
        if (!USERNAME.matcher(username).matches()) {
            log.warn("MASTER_USERNAME이 username 규칙(소문자·숫자 4~10자)에 맞지 않아 MASTER 계정 생성을 건너뛴다: {}", username);
            return;
        }
        if (userRepository.existsByUsernameIncludingDeleted(username)) {
            return;
        }
        try {
            userRepository.saveAndFlush(User.master(username, passwordEncoder.encode(properties.password())));
            log.info("MASTER 계정을 만들었다: {}", username);
        } catch (DataIntegrityViolationException e) {
            // 같은 서비스 인스턴스 여러 개가 동시에 기동하면 다른 인스턴스가 먼저 만들 수 있다. username 유니크 제약이 중복을 막는다.
            log.info("MASTER 계정을 다른 인스턴스가 먼저 만들었다: {}", username);
        }
    }
}
