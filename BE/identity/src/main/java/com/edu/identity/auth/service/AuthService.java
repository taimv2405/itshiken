package com.edu.identity.auth.service;

import com.edu.identity.auth.dto.internal.LoginResult;
import com.edu.identity.auth.dto.internal.RefreshResult;
import com.edu.identity.auth.dto.request.LoginRequest;
import com.edu.identity.auth.dto.request.RegisterRequest;
import com.edu.identity.auth.dto.response.RegisterResponse;

public interface AuthService {
    RegisterResponse register(RegisterRequest request);
    LoginResult login(LoginRequest request);
    RefreshResult refresh(String refreshToken);
    void logout(String refreshToken);
}
