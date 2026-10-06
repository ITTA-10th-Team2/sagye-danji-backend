package com.gyejoldanji.global.config;

import com.gyejoldanji.domain.auth.repository.AuthSessionRepository;
import com.gyejoldanji.global.security.SecurityTestConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Swagger 서버 주소가 localhost로 고정되지 않고, 운영 nginx의 X-Forwarded-*를 반영한 https 주소로 나오는지 검증한다.
 * {@code server.forward-headers-strategy=framework}가 등록하는 {@link ForwardedHeaderFilter}를 MockMvc에 직접 붙인다.
 */
class SwaggerServerUrlTest {

    @ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
            SpringDocWebMvcConfiguration.class})
    @Import({SecurityTestConfig.class, SwaggerConfig.class})
    static class DocsConfig {
    }

    abstract static class DocsTest {

        @MockitoBean
        AuthSessionRepository sessionRepository;
        @Autowired
        WebApplicationContext context;

        MockMvc mockMvc() {
            return MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(new ForwardedHeaderFilter())
                    .apply(springSecurity())
                    .build();
        }
    }

    @Nested
    @SpringBootTest(classes = DocsConfig.class)
    class WithoutFixedServerUrl extends DocsTest {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) throws Exception {
            SecurityTestConfig.register(registry);
        }

        @Test
        void usesForwardedHttpsHostBehindNginx() throws Exception {
            mockMvc().perform(get("/v3/api-docs")
                            .header("Host", "app:8080")
                            .header("X-Forwarded-Proto", "https")
                            .header("X-Forwarded-Host", "api.sagye-danji.site")
                            .header("X-Forwarded-For", "203.0.113.10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.servers[0].url").value("https://api.sagye-danji.site"));
        }

        @Test
        void usesRequestHostLocally() throws Exception {
            mockMvc().perform(get("/v3/api-docs").header("Host", "localhost:8080"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.servers[0].url").value("http://localhost:8080"));
        }
    }

    @Nested
    @SpringBootTest(classes = DocsConfig.class, properties = {
            "app.swagger.server-url=https://api.sagye-danji.site",
            "app.swagger.server-description=Production"})
    class WithFixedServerUrl extends DocsTest {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) throws Exception {
            SecurityTestConfig.register(registry);
        }

        @Test
        void usesConfiguredServerUrl() throws Exception {
            mockMvc().perform(get("/v3/api-docs").header("Host", "localhost:8080"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.servers.length()").value(1))
                    .andExpect(jsonPath("$.servers[0].url").value("https://api.sagye-danji.site"))
                    .andExpect(jsonPath("$.servers[0].description").value("Production"));
        }
    }
}
