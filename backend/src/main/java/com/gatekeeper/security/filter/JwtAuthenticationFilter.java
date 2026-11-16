package com.gatekeeper.security.filter;

import com.gatekeeper.common.exception.AdminAuthenticationException;
import com.gatekeeper.security.dto.AdminJwtClaims;
import com.gatekeeper.security.entity.AdminUser;
import com.gatekeeper.security.repository.AdminUserRepository;
import com.gatekeeper.security.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final GatekeeperAuthenticationEntryPoint authenticationEntryPoint;
    private final AdminUserRepository adminUserRepository;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            GatekeeperAuthenticationEntryPoint authenticationEntryPoint,
            @Autowired(required = false) AdminUserRepository adminUserRepository) {
        this.jwtService = jwtService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.adminUserRepository = adminUserRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        try {
            AdminJwtClaims claims = jwtService.parseAndValidateToken(token);

            // Real-time check: ensure inactive administrators cannot access protected operations
            if (adminUserRepository != null) {
                Optional<AdminUser> adminOpt = adminUserRepository.findById(claims.adminId());
                if (adminOpt.isEmpty() || !adminOpt.get().isActive()) {
                    log.warn("Access denied for deactivated or revoked administrator: {}", claims.username());
                    SecurityContextHolder.clearContext();
                    authenticationEntryPoint.commence(
                        request,
                        response,
                        new BadCredentialsException("Administrator account is inactive or revoked")
                    );
                    return;
                }
            }

            List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(claims.role()));
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                claims.username(),
                null,
                authorities
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (AdminAuthenticationException e) {
            log.warn("JWT authentication rejected: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(
                request,
                response,
                new BadCredentialsException(e.getMessage(), e)
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}
