package com.sparta.ticket.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.support.PostgresIntegrationTest;
import com.sparta.ticket.user.application.AuthService;
import com.sparta.ticket.user.application.UserQueryService;
import com.sparta.ticket.user.application.dto.LoginCommand;
import com.sparta.ticket.user.application.dto.SignupCommand;
import com.sparta.ticket.user.application.dto.SignupResult;
import com.sparta.ticket.user.domain.UserErrorCode;
import com.sparta.ticket.user.domain.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
@TestPropertySource(
        properties = {"jwt.secret=test-only-jwt-secret-key-0123456789abcdef", "jwt.expiration-seconds=3600"})
class UserAuthIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "Sparta!2026";

    @Autowired
    private AuthService authService;

    @Autowired
    private UserQueryService userQueryService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private static SignupCommand signup(String username) {
        return new SignupCommand(username, PASSWORD, "홍길동");
    }

    @Test
    @DisplayName("회원가입하면 BCrypt 해시, USER 권한, Audit(SYSTEM)이 실제 DB에 저장된다")
    void signupPersists() {
        SignupResult result = authService.signup(signup("itsave01"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT password, role, created_by, updated_by, created_at FROM p_users WHERE user_id = ?",
                result.userId());
        assertThat((String) row.get("password")).startsWith("$2a$").isNotEqualTo(PASSWORD);
        assertThat(row.get("role")).isEqualTo("USER");
        assertThat(row.get("created_by")).isEqualTo("SYSTEM");
        assertThat(row.get("updated_by")).isEqualTo("SYSTEM");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    @DisplayName("논리 삭제된 사용자는 로그인 실패, 내부 조회 404이고, 같은 username으로 다시 가입할 수 없다")
    void withdrawnUser() {
        SignupResult result = authService.signup(signup("itdel01"));
        transactionTemplate.executeWithoutResult(
                status -> userRepository.findById(result.userId()).orElseThrow().softDelete("master01"));

        assertThatThrownBy(() -> authService.login(new LoginCommand("itdel01", PASSWORD)))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.LOGIN_FAILED);
        assertThatThrownBy(() -> userQueryService.getInternalUser(result.userId()))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);
        assertThatThrownBy(() -> authService.signup(signup("itdel01")))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.DUPLICATE_USERNAME);
    }

    @Test
    @DisplayName("같은 username으로 동시에 가입하면 하나만 성공하고 나머지는 500이 아니라 DUPLICATE_USERNAME")
    void concurrentSignup() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    return authService.signup(signup("itrace01"));
                } catch (BusinessException e) {
                    return e.getErrorCode();
                }
            }));
        }
        start.countDown();

        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();

        assertThat(results).filteredOn(SignupResult.class::isInstance).hasSize(1);
        assertThat(results)
                .filteredOn(result -> !(result instanceof SignupResult))
                .hasSize(threads - 1)
                .containsOnly(UserErrorCode.DUPLICATE_USERNAME);
    }

    @Test
    @DisplayName("내부 사용자 조회는 DTO를 감싸지 않고 Gateway UserSnapshot과 같은 필드명으로 반환한다")
    void internalUserContract() throws Exception {
        SignupResult result = authService.signup(signup("itapi01"));

        mockMvc.perform(get("/internal/users/{userId}", result.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(result.userId().toString()))
                .andExpect(jsonPath("$.username").value("itapi01"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("테이블은 Hibernate가 아니라 backend/sql/user-service/schema.sql로 만들어진다(이름 붙인 제약이 있다)")
    void schemaFromSqlFile() {
        assertThat(jdbcTemplate.queryForList(
                        "SELECT conname FROM pg_constraint WHERE conrelid = 'p_users'::regclass", String.class))
                .contains("uk_p_users_username", "ck_p_users_role");
    }
}
