package com.example.backend.repository;

import com.example.backend.domain.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    List<AppUser> findByRoleInAndActiveTrue(List<com.example.backend.domain.enums.UserRole> roles);
}
