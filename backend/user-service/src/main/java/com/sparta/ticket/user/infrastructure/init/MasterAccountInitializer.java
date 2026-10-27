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
 * 회원가입은 항상 USER로 만들어지므로, 첫 MASTER는 기동할 때 환경 변수(MASTER_EMAIL, MASTER_PASSWORD)로 만든다.
 * 값이 비어 있거나 같은 email이 이미 있으면(탈퇴자 포함) 아무것도 하지 않는다. 기존 계정의 권한은 바꾸지 않는다.
 *
 * Note: 메서드에 @Transactional을 걸지 않는다. 걸면 저장 실패 시 트랜잭션이 rollback-only로 표시돼, 예외를 잡아도
 * 메서드가 끝날 때 커밋하면서 UnexpectedRollbackException이 난다. saveAndFlush가 자기 트랜잭션에서 INSERT까지 실행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(MasterAccountProperties.class)
public class MasterAccountInitializer implements ApplicationRunner {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

    private final MasterAccountProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.email();
        if (!StringUtils.hasText(email) || !StringUtils.hasText(properties.password())) {
            log.info("MASTER_EMAIL 또는 MASTER_PASSWORD가 비어 있어 MASTER 계정 생성을 건너뛴다.");
            return;
        }
        if (email.length() > 100 || !EMAIL.matcher(email).matches()) {
            log.warn("MASTER_EMAIL이 이메일 형식(100자 이하)에 맞지 않아 MASTER 계정 생성을 건너뛴다: {}", email);
            return;
        }
        if (userRepository.existsByEmailIncludingDeleted(email)) {
            return;
        }
        try {
            userRepository.saveAndFlush(User.master(email, passwordEncoder.encode(properties.password())));
            log.info("MASTER 계정을 만들었다: {}", email);
        } catch (DataIntegrityViolationException e) {
            // 같은 서비스 인스턴스 여러 개가 동시에 기동하면 다른 인스턴스가 먼저 만들 수 있다. email 유니크 제약이 중복을 막는다.
            log.info("MASTER 계정을 다른 인스턴스가 먼저 만들었다: {}", email);
        }
    }
}
