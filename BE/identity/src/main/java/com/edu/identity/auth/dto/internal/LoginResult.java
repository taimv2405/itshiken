package com.edu.identity.auth.dto.internal;

public record LoginResult(String accessToken, String refreshToken, Long userId) {}
