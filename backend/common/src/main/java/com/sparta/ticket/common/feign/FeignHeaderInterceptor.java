package com.sparta.ticket.common.feign;

import com.sparta.ticket.common.auth.UserHeaders;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Note: 서비스 간 Feign 호출에 현재 요청의 X-User-* 헤더를 그대로 실어 보낸다. 호출받는 서비스도 같은 사용자로
 * 권한을 판단하고 Audit을 남길 수 있다. 요청 밖(스케줄러 등)에서 호출하면 전달할 헤더가 없으므로 건너뛴다.
 */
public class FeignHeaderInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }
        for (String name : UserHeaders.ALL) {
            String value = attributes.getRequest().getHeader(name);
            if (StringUtils.hasText(value)) {
                template.header(name, value);
            }
        }
    }
}
