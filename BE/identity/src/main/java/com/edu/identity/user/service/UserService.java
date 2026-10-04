package com.edu.identity.user.service;

import com.edu.identity.user.dto.CreateUserRequest;
import com.edu.identity.user.entity.User;
import com.edu.identity.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public User createUser(CreateUserRequest request) {

        if (userRepository.existsByName(request.getName())) {
            throw new RuntimeException("Người dùng đã tồn tại");
        }

        User user = new User();
        user.setName(request.getName());
        System.out.println(request.getCurrent());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setCurrentStatus(request.getCurrent());
        user.setEmail(request.getEmail());
        return userRepository.save(user);

    }

    public User getUserById(Long id){
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Người dùng không tồn tại"));
    }


}