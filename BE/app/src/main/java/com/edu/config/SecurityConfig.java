package com.edu.config;

import com.edu.common.security.JwtAuthFilter;
import com.edu.common.security.JwtService;
import com.edu.common.web.RequestTimingFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    private static final String UNAUTHORIZED_MESSAGE = "Bạn chưa đăng nhập hoặc phiên đăng nhập đã hết hạn";

    /**
     * material_service had no Spring Security, so its responses carry only the 4 gateway headers
     * and none of the Spring Security defaults (Cache-Control, Pragma, Expires, X-XSS-Protection).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain materialFilterChain(HttpSecurity http, JwtService jwtService, ObjectMapper objectMapper)
            throws Exception {
        http.securityMatcher("/api/materials/**");
        applyCommon(http, jwtService, objectMapper);
        http.headers(headers -> {
            headers.defaultsDisabled();
            gatewayHeaders(headers);
            headers.contentTypeOptions(Customizer.withDefaults());
        });
        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers(HttpMethod.POST, "/api/materials").authenticated();
            auth.requestMatchers(HttpMethod.PUT, "/api/materials/**").authenticated();
            auth.requestMatchers(HttpMethod.DELETE, "/api/materials/**").authenticated();
            auth.anyRequest().permitAll();
        });
        return http.build();
    }

    /** Auth, user, exam and ai: Spring Security default headers plus the 4 gateway headers. */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, ObjectMapper objectMapper,
                                                   @Value("${app.admin.ingest-enabled:false}") boolean ingestEnabled)
            throws Exception {
        applyCommon(http, jwtService, objectMapper);
        http.headers(headers -> {
            gatewayHeaders(headers);
            // The old gateway wrote its 401 itself, so that response never had these headers.
            headers.cacheControl(cache -> cache.disable());
            headers.xssProtection(xss -> xss.disable());
            headers.addHeaderWriter(skipOn401(new CacheControlHeadersWriter()));
            headers.addHeaderWriter(skipOn401(new XXssProtectionHeaderWriter()));
        });
        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers(HttpMethod.GET, "/api/users/me").authenticated();
            auth.requestMatchers(HttpMethod.POST, "/api/exams").authenticated();
            auth.requestMatchers(HttpMethod.PUT, "/api/exams/**").authenticated();
            auth.requestMatchers(HttpMethod.DELETE, "/api/exams/**").authenticated();
            auth.requestMatchers(HttpMethod.POST, "/api/exams/*/submit").authenticated();
            auth.requestMatchers(HttpMethod.POST, "/api/exams/*/rating").authenticated();
            auth.requestMatchers(HttpMethod.GET, "/api/attempts/me/**").authenticated();
            if (!ingestEnabled) {
                auth.requestMatchers("/api/ai/admin/**").denyAll();
            }
            auth.anyRequest().permitAll();
        });
        return http.build();
    }

    private static HeaderWriter skipOn401(HeaderWriter delegate) {
        return (request, response) -> {
            if (response.getStatus() != HttpServletResponse.SC_UNAUTHORIZED) {
                delegate.writeHeaders(request, response);
            }
        };
    }

    private void gatewayHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers.frameOptions(frame -> frame.sameOrigin())
                .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicyHeader(p -> p.policy("geolocation=(), microphone=(), camera=()"));
    }

    private void applyCommon(HttpSecurity http, JwtService jwtService, ObjectMapper objectMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // No anonymous token: denyAll must answer 403, not 401.
                .anonymous(anon -> anon.disable())
                .cors(Customizer.withDefaults())
                .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, e) -> {
                    Object start = request.getAttribute(RequestTimingFilter.START_TIME_ATTR);
                    long elapsed = start instanceof Long s ? System.currentTimeMillis() - s : 0;
                    ObjectNode body = objectMapper.createObjectNode();
                    body.put("success", false);
                    body.put("statusCode", HttpServletResponse.SC_UNAUTHORIZED);
                    body.put("message", UNAUTHORIZED_MESSAGE);
                    body.putNull("data");
                    body.put("path", request.getRequestURI());
                    body.put("timestamp", Instant.now().toString());
                    body.put("responseTime", elapsed + " ms");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getOutputStream().write(objectMapper.writeValueAsBytes(body));
                }));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${cors.allowed-origins:}") String allowedOrigins) {
        List<String> origins = new ArrayList<>();
        Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).forEach(origins::add);

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
