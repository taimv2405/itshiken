package com.edu.identity.user.controller;

import com.edu.identity.user.dto.CreateUserRequest;
import com.edu.identity.user.dto.UserProfileDto;
import com.edu.identity.user.entity.User;
import com.edu.identity.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public User createUser(@Valid @RequestBody CreateUserRequest request) {
        return userService.createUser(request);
    }

    @GetMapping("/{id}")
    public UserProfileDto getUserById(@PathVariable Long id) {
        User u = userService.getUserById(id);
        return UserProfileDto.builder()
                .id(u.getId())
                .name(u.getName())
                .currentStatus(u.getCurrentStatus())
                .createdAt(u.getCreatedAt())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> getMe(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User u = userService.getUserById(Long.parseLong(userId));
        return ResponseEntity.ok(UserProfileDto.builder()
                .id(u.getId())
                .name(u.getName())
                .phoneNumber(u.getPhoneNumber())
                .currentStatus(u.getCurrentStatus())
                .email(u.getEmail())
                .createdAt(u.getCreatedAt())
                .build());
    }
}
