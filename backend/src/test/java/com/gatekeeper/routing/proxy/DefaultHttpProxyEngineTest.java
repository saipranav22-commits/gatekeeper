package com.gatekeeper.routing.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.routing.model.GatewayRoute;
import com.gatekeeper.routing.validator.SsrfProtectionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultHttpProxyEngine Unit Tests")
class DefaultHttpProxyEngineTest {

    @Mock
    private HttpClient httpClient;

    private DefaultHttpProxyEngine proxyEngine;
    private ObjectMapper objectMapper;
    private GatewayRoute route;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        SsrfProtectionValidator ssrfValidator = new SsrfProtectionValidator();
        proxyEngine = new DefaultHttpProxyEngine(httpClient, ssrfValidator, objectMapper);

        route = new GatewayRoute(
            "orders-service-v1",
            "/api/v1/proxy/orders/**",
            "/api/v1/proxy",
            "orders-service",
            URI.create("http://localhost:8081"),
            Set.of("GET", "POST"),
            Map.of("GET", "orders:read", "POST", "orders:write"),
            "orders:read",
            true,
            5000,
            1048576L
        );
    }

    @Test
    @DisplayName("Successfully forwards GET request and streams response body")
    @SuppressWarnings("unchecked")
    void shouldForwardGetRequestSuccessfully() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/proxy/orders/ORD-001");
        request.setAttribute("GATEKEEPER_CORRELATION_ID", "req-123");
        request.addHeader("User-Agent", "GatekeeperClient/1.0");
        request.addHeader("X-API-Key", "secret-key-to-strip");
        request.addHeader("Authorization", "Bearer secret-token");
        request.addHeader("Connection", "keep-alive");

        MockHttpServletResponse response = new MockHttpServletResponse();

        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        doReturn(200).when(mockResponse).statusCode();
        doReturn(new ByteArrayInputStream("{\"id\":\"ORD-001\"}".getBytes(StandardCharsets.UTF_8))).when(mockResponse).body();
        doReturn(HttpHeaders.of(
            Map.of("Content-Type", List.of("application/json"), "X-Upstream-Header", List.of("custom-val")),
            (k, v) -> true
        )).when(mockResponse).headers();

        doReturn(mockResponse).when(httpClient).send(any(HttpRequest.class), any());

        proxyEngine.forward(request, response, route, "/orders/ORD-001");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"ORD-001\"}");
        assertThat(response.getHeader("X-Upstream-Header")).isEqualTo("custom-val");

        // Verify outgoing request headers stripped sensitive credentials and hop-by-hop
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any());
        HttpRequest forwardedReq = requestCaptor.getValue();

        assertThat(forwardedReq.uri()).isEqualTo(URI.create("http://localhost:8081/orders/ORD-001"));
        assertThat(forwardedReq.headers().firstValue("X-API-Key")).isEmpty();
        assertThat(forwardedReq.headers().firstValue("Authorization")).isEmpty();
        assertThat(forwardedReq.headers().firstValue("Connection")).isEmpty();
        assertThat(forwardedReq.headers().firstValue("X-Request-Id")).contains("req-123");
        assertThat(forwardedReq.headers().firstValue("User-Agent")).contains("GatekeeperClient/1.0");
    }

    @Test
    @DisplayName("Return HTTP 502 Bad Gateway when upstream connection is refused")
    void shouldReturn502OnConnectionRefused() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/proxy/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        doThrow(new ConnectException("Connection refused to http://localhost:8081"))
            .when(httpClient).send(any(HttpRequest.class), any());

        proxyEngine.forward(request, response, route, "/orders");

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString()).contains("Bad Gateway");
        assertThat(request.getAttribute("GATEKEEPER_UPSTREAM_STATUS")).isEqualTo(502);
    }

    @Test
    @DisplayName("Return HTTP 504 Gateway Timeout when upstream times out")
    void shouldReturn504OnTimeout() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/proxy/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        doThrow(new HttpTimeoutException("Request timed out"))
            .when(httpClient).send(any(HttpRequest.class), any());

        proxyEngine.forward(request, response, route, "/orders");

        assertThat(response.getStatus()).isEqualTo(504);
        assertThat(response.getContentAsString()).contains("Gateway Timeout");
        assertThat(request.getAttribute("GATEKEEPER_UPSTREAM_STATUS")).isEqualTo(504);
    }

    @Test
    @DisplayName("Return HTTP 413 Payload Too Large when request body exceeds configured limit")
    void shouldReturn413WhenPayloadExceedsLimit() throws Exception {
        GatewayRoute smallLimitRoute = new GatewayRoute(
            "small-limit-route",
            "/api/v1/proxy/small/**",
            "/api/v1/proxy",
            "small-service",
            URI.create("http://localhost:8081"),
            Set.of("POST"),
            Map.of(),
            null,
            true,
            5000,
            10L // Max 10 bytes
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/proxy/small");
        request.setContent("This payload is definitely longer than 10 bytes".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        proxyEngine.forward(request, response, smallLimitRoute, "/small");

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("Request payload exceeds maximum allowed size");
    }
}
