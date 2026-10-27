package com.sparta.ticket.user.presentation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sparta.ticket.user.application.dto.UpdateMyInfoCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 내 정보 수정 요청. 바꿀 필드만 보내고, 빠졌거나 null인 필드는 그대로 둔다.
 *
 * <p>Note: 필드를 생략할 수 있어야 해서 @NotBlank를 붙이지 않는다. @Size·@Pattern은 null을 통과시키므로 보낸 값만 검사한다.
 */
public record UpdateMyInfoRequest(
        @Size(max = 50) @Pattern(regexp = ".*\\S.*", message = "공백만으로 된 이름은 쓸 수 없습니다.")
        String name,

        @Pattern(regexp = "^01[016789]-\\d{3,4}-\\d{4}$", message = "010-1234-5678 형식이어야 합니다.")
        String phone) {

    /**
     * Note: 공통 예외 처리는 필드 오류만 errors에 담는다. isXxx() 형태로 두면 Bean Validation이 이를 속성(anyFieldPresent)으로
     * 보고 필드 오류로 보고한다. Jackson도 isXxx()를 속성으로 다뤄 API 문서의 요청 스키마에 나타나므로 숨긴다.
     */
    @JsonIgnore
    @AssertTrue(message = "바꿀 값(name, phone)이 하나 이상 있어야 합니다.")
    public boolean isAnyFieldPresent() {
        return name != null || phone != null;
    }

    public UpdateMyInfoCommand toCommand() {
        return new UpdateMyInfoCommand(name, phone);
    }
}
