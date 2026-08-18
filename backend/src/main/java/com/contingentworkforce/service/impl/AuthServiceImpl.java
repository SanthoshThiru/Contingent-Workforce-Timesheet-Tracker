package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.auth.AuthResponse;
import com.contingentworkforce.dto.auth.LoginRequest;
import com.contingentworkforce.dto.auth.RegisterRequest;
import com.contingentworkforce.dto.auth.UserResponse;
import com.contingentworkforce.entity.User;
import com.contingentworkforce.enums.UserStatus;
import com.contingentworkforce.exception.DuplicateResourceException;
import com.contingentworkforce.exception.ResourceNotFoundException;

import com.contingentworkforce.repository.UserRepository;
import com.contingentworkforce.security.CustomUserDetails;
import com.contingentworkforce.security.JwtService;
import com.contingentworkforce.security.SecurityUtils;
import com.contingentworkforce.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail().toLowerCase().trim(), request.getPassword())
        );

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        String token = jwtService.generateToken(userDetails);

        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationMs())
                .user(mapToUserResponse(user))
                .build();
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.getEmail().toLowerCase().trim();
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("User already exists with email: " + email);
        }

        User user = User.builder()
                .name(request.getName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .phone(request.getPhone())
                .status(UserStatus.ACTIVE)
                .build();

        User savedUser = userRepository.save(user);
        return mapToUserResponse(savedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {
        UUID userId = SecurityUtils.getCurrentUserId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        return mapToUserResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        return mapToUserResponse(user);
    }

    public static UserResponse mapToUserResponse(User user) {
        if (user == null) return null;
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .phone(user.getPhone())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
