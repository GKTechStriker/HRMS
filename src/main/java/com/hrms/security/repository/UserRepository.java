package com.hrms.security.repository;

import com.hrms.security.entity.Role;
import com.hrms.security.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByHrCode(String hrCode);
    List<User> findByRoleAndActiveTrue(Role role);
    boolean existsByEmailIgnoreCase(String email);
}
