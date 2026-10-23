package com.sparta.ticket.common.autoconfigure;

import com.sparta.ticket.common.auth.CurrentUser;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * 각 서비스의 OpenAPI 문서 공통 설정. 문서는 Gateway Swagger UI(http://localhost:8080/swagger-ui.html)에서 서비스별로 본다.
 * Note: 서버 주소를 상대 경로 "/"로 두면 Swagger UI의 Try it out 요청이 문서를 연 주소(Gateway)로 간다. 서비스 내부 IP가
 * 문서에 찍혀 외부에서 호출할 수 없는 문제를 피한다.
 * 서비스가 OpenAPI 빈을 직접 정의하면 이 빈은 빠진다. 끄기: {@code common.openapi.enabled=false}
 */
@AutoConfiguration
@ConditionalOnClass({OpenAPI.class, SpringDocUtils.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "common.openapi", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommonOpenApiAutoConfiguration {

    private static final String BEARER = "bearerAuth";

    static {
        // @CurrentUser 파라미터는 Gateway 헤더에서 채워지므로 문서의 요청 파라미터로 보이지 않게 한다
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser.class);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI openAPI(@Value("${spring.application.name}") String applicationName) {
        return new OpenAPI()
                .info(new Info().title(applicationName + " API").version("v1"))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
