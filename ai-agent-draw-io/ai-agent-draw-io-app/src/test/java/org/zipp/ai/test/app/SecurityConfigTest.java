package org.zipp.ai.test.app;

import org.junit.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.zipp.ai.config.SecurityConfig;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

public class SecurityConfigTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(TestApplication.class)
            .withPropertyValues("app.security.allowed-origins=http://localhost:3000,https://app.example.com");

    @Test
    public void corsRejectsUntrustedCredentialedOrigin() throws Exception {
        perform(options("/api/v1/test/mutate")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void corsAllowsConfiguredOriginWithCredentials() throws Exception {
        perform(options("/api/v1/test/mutate")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    public void corsAllowsChatStreamTraceRequestHeader() throws Exception {
        perform(options("/api/v1/test/mutate")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                // Chat streaming correlates the client request with the trace run.
                .header("Access-Control-Request-Headers", "content-type,x-xsrf-token,x-workspace-id,x-request-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("x-request-id")));
    }

    @Test
    public void mutatingRoutesRequireCsrfToken() throws Exception {
        perform(post("/api/v1/test/mutate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void mutatingRoutesAcceptValidCsrfToken() throws Exception {
        perform(post("/api/v1/test/mutate")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    public void publicAuthEntryPointsStayCallableWithoutCsrf() throws Exception {
        perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("login"));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        ResultActions[] actions = new ResultActions[1];
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                    .apply(springSecurity())
                    .build();
            actions[0] = mvc.perform(request);
        });
        return actions[0];
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfig.class, TestController.class})
    static class TestApplication {
    }

    @RestController
    static class TestController {

        @PostMapping("/api/v1/test/mutate")
        public String mutate() {
            return "ok";
        }

        @PostMapping("/api/v1/auth/login")
        public String login() {
            return "login";
        }
    }
}
