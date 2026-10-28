package com.sparta.ticket.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.support.PostgresIntegrationTest;
import com.sparta.ticket.user.application.AuthService;
import com.sparta.ticket.user.application.UserQueryService;
import com.sparta.ticket.user.application.dto.LoginCommand;
import com.sparta.ticket.user.application.dto.SignupCommand;
import com.sparta.ticket.user.application.dto.SignupResult;
import com.sparta.ticket.user.domain.UserErrorCode;
import com.sparta.ticket.user.domain.UserRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
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
import org.springframework.http.MediaType;
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

    private static SignupCommand signup(String email) {
        return new SignupCommand(email, PASSWORD, "홍길동", "010-1234-5678");
    }

    @Test
    @DisplayName("회원가입하면 BCrypt 해시, USER 권한, ACTIVE 상태, Audit(SYSTEM)이 실제 DB에 저장된다")
    void signupPersists() {
        SignupResult result = authService.signup(signup("itsave01@ticket.com"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT password, role, status, created_by, updated_by, created_at FROM p_users WHERE user_id = ?",
                result.userId());
        assertThat((String) row.get("password")).startsWith("$2a$").isNotEqualTo(PASSWORD);
        assertThat(row.get("role")).isEqualTo("USER");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("created_by")).isEqualTo("SYSTEM");
        assertThat(row.get("updated_by")).isEqualTo("SYSTEM");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    @DisplayName("판매자 회원가입은 요청 본문의 role을 무시하고 SELLER로 저장하며, 로그인할 수 있다")
    void sellerSignupIgnoresRequestedRole() throws Exception {
        mockMvc.perform(post("/api/auth/seller/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "itseller01@ticket.com", "password": "%s", "name": "김판매",
                                 "phone": "010-1234-5678", "role": "MASTER"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("SELLER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT role FROM p_users WHERE email = ?", String.class, "itseller01@ticket.com"))
                .isEqualTo("SELLER");
        String token = authService
                .login(new LoginCommand("itseller01@ticket.com", PASSWORD))
                .accessToken();
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"role\":\"SELLER\"");
    }

    @Test
    @DisplayName("일반 회원 email로는 판매자로 가입할 수 없다(1 email = 1 계정)")
    void sellerSignupWithUserEmail() {
        authService.signup(signup("itseller02@ticket.com"));

        assertThatThrownBy(() -> authService.signupSeller(signup("itseller02@ticket.com")))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    @DisplayName("논리 삭제된 사용자는 로그인 실패, 내부 조회 404이고, 같은 email로 다시 가입할 수 없다")
    void withdrawnUser() {
        SignupResult result = authService.signup(signup("itdel01@ticket.com"));
        transactionTemplate.executeWithoutResult(
                status -> userRepository.findById(result.userId()).orElseThrow().softDelete("master01"));

        assertThatThrownBy(() -> authService.login(new LoginCommand("itdel01@ticket.com", PASSWORD)))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.LOGIN_FAILED);
        assertThatThrownBy(() -> userQueryService.getInternalUser(result.userId()))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);
        assertThatThrownBy(() -> authService.signup(signup("itdel01@ticket.com")))
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    @DisplayName("논리 삭제 없이 WITHDRAWN 상태만 바뀌어도 내부 인증 조회는 404다")
    void inactiveUserIsNotAnAuthenticationPrincipal() throws Exception {
        SignupResult result = authService.signup(signup("itstatus01@ticket.com"));
        mockMvc.perform(get("/internal/users/{userId}", result.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
        jdbcTemplate.update("UPDATE p_users SET status = 'WITHDRAWN' WHERE user_id = ?", result.userId());
        mockMvc.perform(get("/internal/users/{userId}", result.userId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("같은 email로 동시에 가입하면 하나만 성공하고 나머지는 500이 아니라 DUPLICATE_EMAIL")
    void concurrentSignup() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    return authService.signup(signup("itrace01@ticket.com"));
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
                .containsOnly(UserErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    @DisplayName("내부 사용자 조회는 DTO를 감싸지 않고 Gateway UserSnapshot과 같은 필드명으로 반환한다")
    void internalUserContract() throws Exception {
        SignupResult result = authService.signup(signup("itapi01@ticket.com"));

        mockMvc.perform(get("/internal/users/{userId}", result.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(result.userId()))
                .andExpect(jsonPath("$.email").value("itapi01@ticket.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("내 정보 수정은 보낸 필드만 DB에 반영하고, updated_by에 본인 email, 응답 updatedAt에 수정 시각을 남긴다")
    void updateMyInfoPersists() throws Exception {
        SignupResult result = authService.signup(signup("itupdate01@ticket.com"));
        // 가입과 수정이 같은 초에 일어나도 updatedAt이 바뀐 것을 구분하도록 수정 전 시각을 과거로 돌려 둔다.
        jdbcTemplate.update(
                "UPDATE p_users SET updated_at = ? WHERE user_id = ?",
                Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0)),
                result.userId());

        String body = mockMvc.perform(patch("/api/users/me")
                        .header(UserHeaders.USER_ID, result.userId().toString())
                        .header(UserHeaders.USERNAME, "itupdate01@ticket.com")
                        .header(UserHeaders.ROLE, "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"010-9876-5432\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("홍길동"))
                .andExpect(jsonPath("$.data.phone").value("010-9876-5432"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT name, phone, created_by, updated_by, updated_at FROM p_users WHERE user_id = ?",
                result.userId());
        assertThat(row.get("name")).isEqualTo("홍길동");
        assertThat(row.get("phone")).isEqualTo("010-9876-5432");
        assertThat(row.get("created_by")).isEqualTo("SYSTEM");
        assertThat(row.get("updated_by")).isEqualTo("itupdate01@ticket.com");
        LocalDateTime updatedAt = ((Timestamp) row.get("updated_at")).toLocalDateTime();
        assertThat(updatedAt).isAfter(LocalDateTime.of(2026, 1, 1, 0, 0));

        // 응답이 수정 전 시각이 아니라 UPDATE 후 DB 값을 담았는지(saveAndFlush) 확인한다.
        String expected = updatedAt
                .truncatedTo(ChronoUnit.SECONDS)
                .atZone(ZoneId.of("Asia/Seoul"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        assertThat((String) JsonPath.read(body, "$.data.updatedAt")).isEqualTo(expected);
    }

    @Test
    @DisplayName("테이블은 Hibernate가 아니라 backend/sql/user-service/schema.sql로 만들어진다(이름 붙인 제약이 있다)")
    void schemaFromSqlFile() {
        assertThat(jdbcTemplate.queryForList(
                        "SELECT conname FROM pg_constraint WHERE conrelid = 'p_users'::regclass", String.class))
                .contains("uk_p_users_email", "ck_p_users_role", "ck_p_users_status");
    }
}
