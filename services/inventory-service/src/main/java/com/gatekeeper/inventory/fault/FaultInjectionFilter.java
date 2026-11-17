package com.gatekeeper.inventory.fault;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Profile("!prod")
public class FaultInjectionFilter extends OncePerRequestFilter {

    private final FaultSimulationManager faultManager;

    public FaultInjectionFilter(FaultSimulationManager faultManager) {
        this.faultManager = faultManager;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path.startsWith("/fault")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Record incoming attempt
        String correlationId = request.getHeader("X-Request-Id");
        faultManager.recordAttempt(correlationId);

        try {
            faultManager.applyFault();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Fault injection interrupted");
            return;
        } catch (SimulatedFaultException e) {
            response.setStatus(e.getStatusCode());
            response.setContentType("application/json");
            response.getWriter().write(String.format("{\"error\":\"SimulatedFault\",\"status\":%d,\"message\":\"%s\"}",
                e.getStatusCode(), e.getMessage()));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
