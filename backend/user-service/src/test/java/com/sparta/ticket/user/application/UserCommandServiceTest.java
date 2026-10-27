package com.sparta.ticket.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.user.UserFixture;
import com.sparta.ticket.user.application.dto.UpdateMyInfoCommand;
import com.sparta.ticket.user.application.dto.UserResult;
import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserErrorCode;
import com.sparta.ticket.user.domain.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserCommandServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserCommandService userCommandService;

    @Test
    @DisplayName("내 정보를 바꾸고 saveAndFlush로 바로 반영한 결과를 돌려준다")
    void updateMyInfo() {
        User user = UserFixture.user("fan@ticket.com", "{hash}");
        UserContext context = new UserContext(user.getId(), "fan@ticket.com", Role.USER);
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        given(userRepository.saveAndFlush(user)).willReturn(user);

        UserResult result = userCommandService.updateMyInfo(context, new UpdateMyInfoCommand("김태현", "010-9876-5432"));

        verify(userRepository).saveAndFlush(user);
        assertThat(result.name()).isEqualTo("김태현");
        assertThat(result.phone()).isEqualTo("010-9876-5432");
        assertThat(result.email()).isEqualTo("fan@ticket.com");
    }

    @Test
    @DisplayName("회원이 없으면(탈퇴 직후 캐시 구간) USER_NOT_FOUND이고 저장하지 않는다")
    void userNotFound() {
        UserContext context = new UserContext(99L, "gone@ticket.com", Role.USER);
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userCommandService.updateMyInfo(context, new UpdateMyInfoCommand("김태현", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(UserErrorCode.USER_NOT_FOUND);
        verify(userRepository, never()).saveAndFlush(any());
    }
}
