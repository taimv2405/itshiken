package com.edu.identity.auth.repository;

import com.edu.identity.auth.entity.Credential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CredentialRepository extends JpaRepository<Credential, Long> {
    Optional<Credential> findByEmail(String email);
}