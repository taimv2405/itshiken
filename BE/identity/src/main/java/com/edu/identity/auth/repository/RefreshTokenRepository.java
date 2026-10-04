package com.edu.identity.auth.repository;

import com.edu.identity.auth.entity.Credential;
import com.edu.identity.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.credential = :credential AND r.revoked = false")
    void revokeAllByCredential(Credential credential);
}
