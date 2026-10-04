package com.edu.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Mirrors the old gateway's JwtAuthGatewayFilter.
 * OPTIONS and /api/auth/**, /actuator/** pass through untouched. A valid access_token cookie
 * overrides X-User-Id with its subject and sets the SecurityContext. An invalid or missing token
 * passes the original request on, so an X-User-Id sent by the client survives (known hole, see
 * "Việc để sau" in the plan; fixed on branch fix/x-user-id-spoofing).
 * Not a @Component on purpose: it is created in SecurityConfig, otherwise Boot would also
 * register it as a servlet filter that runs before Spring Security.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "access_token";
    public static final String USER_ID_HEADER = "X-User-Id";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final List<String> EXCLUDED_PATHS = List.of("/api/auth/**", "/actuator/**");

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod()) || isExcluded(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);
        String userId = token != null ? jwtService.extractUserIdIfValid(token) : null;

        if (userId == null) {
            chain.doFilter(request, response);
            return;
        }
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        chain.doFilter(new UserIdHeaderRequest(request, userId), response);
    }

    private static boolean isExcluded(String path) {
        return EXCLUDED_PATHS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private static String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static class UserIdHeaderRequest extends HttpServletRequestWrapper {

        private final String userId;

        UserIdHeaderRequest(HttpServletRequest request, String userId) {
            super(request);
            this.userId = userId;
        }

        @Override
        public String getHeader(String name) {
            if (USER_ID_HEADER.equalsIgnoreCase(name)) {
                return userId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (USER_ID_HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(userId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(USER_ID_HEADER::equalsIgnoreCase);
            names.add(USER_ID_HEADER);
            return Collections.enumeration(names);
        }
    }
}
