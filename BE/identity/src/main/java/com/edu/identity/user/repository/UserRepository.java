package com.edu.identity.user.repository;

import com.edu.identity.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByName(String username);
    boolean existsById(Long id);
}