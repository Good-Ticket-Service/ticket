package com.sparta.ticket.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserContextTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_ID = 2L;

    private final UserContext owner = new UserContext(OWNER_ID, "owner01", Role.USER);
    private final UserContext master = new UserContext(OTHER_ID, "master", Role.MASTER);
    private final UserContext stranger = new UserContext(OTHER_ID, "other01", Role.USER);

    @Test
    @DisplayName("본인 여부는 사용자 ID로 판단한다")
    void isSelf() {
        assertThat(owner.isSelf(OWNER_ID)).isTrue();
        assertThat(stranger.isSelf(OWNER_ID)).isFalse();
        assertThat(owner.isSelf(null)).isFalse();
    }

    @Test
    @DisplayName("본인이거나 MASTER면 소유자 검사를 통과한다")
    void selfOrMasterPasses() {
        assertThatCode(() -> owner.validateSelfOrMaster(OWNER_ID)).doesNotThrowAnyException();
        assertThatCode(() -> master.validateSelfOrMaster(OWNER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("본인도 MASTER도 아니면 403 FORBIDDEN")
    void strangerIsForbidden() {
        assertThatThrownBy(() -> stranger.validateSelfOrMaster(OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("MASTER 검사는 USER를 403으로 막는다")
    void validateMaster() {
        assertThatCode(master::validateMaster).doesNotThrowAnyException();
        assertThatThrownBy(owner::validateMaster)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("헬퍼 메서드는 JSON 응답 속성으로 나가지 않는다")
    void helpersAreNotSerialized() throws Exception {
        String json = new ObjectMapper().writeValueAsString(owner);

        assertThat(json).contains("\"userId\"", "\"username\"", "\"role\"");
        assertThat(json).doesNotContain("master", "self");
    }
}
