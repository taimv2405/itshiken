package com.edu.identity.auth.service;

import com.edu.identity.auth.dto.internal.LoginResult;
import com.edu.identity.auth.dto.request.LoginRequest;
import com.edu.identity.auth.dto.request.RegisterRequest;
import com.edu.identity.auth.dto.response.RegisterResponse;
import com.edu.identity.auth.entity.Credential;
import com.edu.identity.auth.exception.AuthException;
import com.edu.identity.auth.exception.DuplicateEmailException;
import com.edu.identity.auth.repository.CredentialRepository;
import com.edu.identity.auth.repository.RefreshTokenRepository;
import com.edu.common.security.JwtService;
import com.edu.identity.user.dto.CreateUserRequest;
import com.edu.identity.user.entity.User;
import com.edu.identity.user.service.UserService;
import com.edu.identity.auth.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — unit tests")
class AuthServiceTest {

    @Mock CredentialRepository  credentialRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock UserService           userService;
    @Mock PasswordEncoder       passwordEncoder;
    @Mock JwtService            jwtService;

    @InjectMocks AuthServiceImpl authService;

    private static User userWithId(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    // ══════════════════════════════ LOGIN ═════════════════════════════════════

    @Test
    @DisplayName("login: email + mật khẩu đúng → trả về LoginResult với token và userId")
    void login_thanhCong_traVeLoginResultVoiTokenVaUserId() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@gmail.com");
        request.setPassword("password123");

        Credential credential = Credential.builder()
                .userId(42L)
                .email("user@gmail.com")
                .password("$2a$08$hashedPassword")
                .build();

        when(credentialRepository.findByEmail("user@gmail.com")).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("password123", "$2a$08$hashedPassword")).thenReturn(true);
        when(jwtService.generateToken(42L)).thenReturn("eyJhbGciOiJIUzI1NiJ9.payload.sig");

        LoginResult result = authService.login(request);

        assertThat(result.accessToken()).isEqualTo("eyJhbGciOiJIUzI1NiJ9.payload.sig");
        assertThat(result.userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("login: email không tồn tại → throw AuthException")
    void login_emailKhongTonTai_throwAuthException() {
        LoginRequest request = new LoginRequest();
        request.setEmail("khongtontai@gmail.com");
        request.setPassword("password123");

        when(credentialRepository.findByEmail("khongtontai@gmail.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AuthException.class)
                .hasMessage("Email không tồn tại");
    }

    @Test
    @DisplayName("login: sai mật khẩu → throw AuthException")
    void login_saiMatKhau_throwAuthException() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@gmail.com");
        request.setPassword("saimatkhau");

        Credential credential = Credential.builder()
                .userId(1L)
                .email("user@gmail.com")
                .password("$2a$08$hashedPassword")
                .build();

        when(credentialRepository.findByEmail("user@gmail.com")).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("saimatkhau", "$2a$08$hashedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AuthException.class)
                .hasMessage("Sai mật khẩu");
    }

    @Test
    @DisplayName("login: KHÔNG gọi UserService (regression guard)")
    void login_khongGoiUserService_baoGio() {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@gmail.com");
        request.setPassword("password123");

        Credential credential = Credential.builder()
                .userId(1L)
                .email("user@gmail.com")
                .password("$2a$08$hash")
                .build();

        when(credentialRepository.findByEmail("user@gmail.com")).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(jwtService.generateToken(1L)).thenReturn("token");

        authService.login(request);

        verifyNoInteractions(userService);
    }

    // ══════════════════════════════ REGISTER ══════════════════════════════════

    @Test
    @DisplayName("register: thành công → lưu credential đúng, trả về credential id")
    void register_thanhCong_luuCredentialVaTraVeId() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Nguyen Van A");
        request.setEmail("user@gmail.com");
        request.setPhoneNumber("0123456789");
        request.setStatus("student");
        request.setPassword("password123");

        User user = userWithId(99L);

        Credential savedCredential = Credential.builder()
                .id(5L).userId(99L).email("user@gmail.com").password("$2a$08$hashed")
                .build();

        when(credentialRepository.findByEmail("user@gmail.com")).thenReturn(Optional.empty());
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(user);
        when(passwordEncoder.encode("password123")).thenReturn("$2a$08$hashed");
        when(credentialRepository.save(any(Credential.class))).thenReturn(savedCredential);

        RegisterResponse result = authService.register(request);

        assertThat(result.getCredentialId()).isEqualTo(5L);
        verify(credentialRepository).save(argThat(cred ->
                cred.getEmail().equals("user@gmail.com") && cred.getUserId().equals(99L)
        ));
    }

    @Test
    @DisplayName("register: email đã tồn tại → throw DuplicateEmailException, KHÔNG gọi UserService")
    void register_emailDaTonTai_throwDuplicateEmailException() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Test");
        request.setEmail("dup@gmail.com");
        request.setPhoneNumber("0123456789");
        request.setStatus("student");
        request.setPassword("password123");

        when(credentialRepository.findByEmail("dup@gmail.com"))
                .thenReturn(Optional.of(Credential.builder().email("dup@gmail.com").build()));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessage("Email đã được sử dụng");

        verifyNoInteractions(userService);
        verify(credentialRepository, never()).save(any());
    }

    @Test
    @DisplayName("register: UserService ném lỗi → lỗi được truyền lên, KHÔNG lưu credential")
    void register_userServiceThatBai_throwExceptionVaKhongLuuCredential() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Test");
        request.setEmail("test@gmail.com");
        request.setPhoneNumber("0123456789");
        request.setStatus("student");
        request.setPassword("password123");

        when(credentialRepository.findByEmail("test@gmail.com")).thenReturn(Optional.empty());
        when(userService.createUser(any())).thenThrow(new RuntimeException("Lỗi tạo user"));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Lỗi tạo user");

        verify(credentialRepository, never()).save(any());
    }

    @Test
    @DisplayName("register: mật khẩu được mã hóa BCrypt trước khi lưu")
    void register_matKhauDuocMaHoa_truocKhiLuu() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Test");
        request.setEmail("test@gmail.com");
        request.setPhoneNumber("0123456789");
        request.setStatus("student");
        request.setPassword("rawPassword");

        User user = userWithId(1L);

        when(credentialRepository.findByEmail("test@gmail.com")).thenReturn(Optional.empty());
        when(userService.createUser(any())).thenReturn(user);
        when(passwordEncoder.encode("rawPassword")).thenReturn("$2a$08$encodedHash");
        when(credentialRepository.save(any())).thenReturn(
                Credential.builder().id(1L).email("test@gmail.com").build()
        );

        authService.register(request);

        verify(credentialRepository).save(argThat(cred ->
                cred.getPassword().equals("$2a$08$encodedHash") &&
                !cred.getPassword().equals("rawPassword")
        ));
    }
}
