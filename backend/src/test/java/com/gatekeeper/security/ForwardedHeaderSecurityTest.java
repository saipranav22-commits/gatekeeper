package com.gatekeeper.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "server.forward-headers-strategy=framework"
})
@AutoConfigureMockMvc
@ActiveProfiles("foundation")
class ForwardedHeaderSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class TestForwardedEndpointConfig {
        @RestController
        @RequestMapping("/api/v1/admin/inspect-headers")
        static class HeaderInspectController {
            @GetMapping
            public ResponseEntity<Map<String, Object>> inspect(HttpServletRequest request) {
                Map<String, Object> map = new HashMap<>();
                map.put("scheme", request.getScheme());
                map.put("isSecure", request.isSecure());
                map.put("serverName", request.getServerName());
                map.put("serverPort", request.getServerPort());
                map.put("remoteAddr", request.getRemoteAddr());
                return ResponseEntity.ok(map);
            }
        }
    }

    @Test
    @DisplayName("Direct HTTP request without forwarded headers reflects plain HTTP connection")
    @WithMockUser(roles = "ADMIN")
    void directHttpRequestReflectsPlainHttp() throws Exception {
        mockMvc.perform(get("/api/v1/admin/inspect-headers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scheme", is("http")))
            .andExpect(jsonPath("$.isSecure", is(false)))
            .andExpect(jsonPath("$.serverName", is("localhost")))
            .andExpect(jsonPath("$.serverPort", is(80)));
    }

    @Test
    @DisplayName("Trusted edge proxy HTTPS termination correctly adapts scheme and isSecure state")
    @WithMockUser(roles = "ADMIN")
    void trustedEdgeProxyHttpsTerminationAdaptsScheme() throws Exception {
        mockMvc.perform(get("/api/v1/admin/inspect-headers")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Port", "443")
                .header("X-Forwarded-Host", "gatekeeper.production.domain"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scheme", is("https")))
            .andExpect(jsonPath("$.isSecure", is(true)))
            .andExpect(jsonPath("$.serverName", is("gatekeeper.production.domain")))
            .andExpect(jsonPath("$.serverPort", is(443)));
    }

    @Test
    @DisplayName("Forwarded client IP adapts remoteAddr when forwarded by upstream proxy")
    @WithMockUser(roles = "ADMIN")
    void forwardedClientIpAdaptsRemoteAddr() throws Exception {
        mockMvc.perform(get("/api/v1/admin/inspect-headers")
                .header("X-Forwarded-For", "203.0.113.195"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.remoteAddr", is("203.0.113.195")));
    }
}
