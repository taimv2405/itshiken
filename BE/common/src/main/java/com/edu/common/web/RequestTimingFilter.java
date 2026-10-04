package com.edu.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTimingFilter extends OncePerRequestFilter {

    public static final String START_TIME_ATTR = "REQUEST_START_TIME";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        request.setAttribute(START_TIME_ATTR, start);
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("[{}] {} {} {} {}ms", requestId, request.getMethod(), request.getRequestURI(),
                    response.getStatus(), System.currentTimeMillis() - start);
        }
    }
}
