package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.auth.UserRequest;
import com.contingentworkforce.dto.auth.UserResponse;
import com.contingentworkforce.entity.User;
import com.contingentworkforce.enums.Role;
import com.contingentworkforce.enums.UserStatus;
import com.contingentworkforce.exception.DuplicateResourceException;
import com.contingentworkforce.exception.ResourceNotFoundException;
import com.contingentworkforce.repository.UserRepository;
import com.contingentworkforce.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getUsers(Role role, String search) {
        List<User> users;
        if (role != null) {
            users = userRepository.findByRole(role);
        } else {
            users = userRepository.findAll();
        }

        if (search != null && !search.trim().isEmpty()) {
            String query = search.trim().toLowerCase();
            users = users.stream()
                    .filter(u -> (u.getName() != null && u.getName().toLowerCase().contains(query))
                            || (u.getEmail() != null && u.getEmail().toLowerCase().contains(query)))
                    .collect(Collectors.toList());
        }

        return users.stream()
                .map(AuthServiceImpl::mapToUserResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
        return AuthServiceImpl.mapToUserResponse(user);
    }

    @Override
    @Transactional
    public UserResponse createUser(UserRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("A user with this email address already exists: " + email);
        }

        String rawPassword = (request.getPassword() != null && !request.getPassword().trim().isEmpty())
                ? request.getPassword().trim()
                : "Password123!";

        User user = User.builder()
                .name(request.getName().trim())
                .email(email)
                .phone(request.getPhone())
                .role(request.getRole() != null ? request.getRole() : Role.MANAGER)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .status(request.getStatus() != null ? request.getStatus() : UserStatus.ACTIVE)
                .build();

        User saved = userRepository.save(user);
        return AuthServiceImpl.mapToUserResponse(saved);
    }

    @Override
    @Transactional
    public UserResponse updateUser(UUID id, UserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        if (request.getEmail() != null && !request.getEmail().trim().equalsIgnoreCase(user.getEmail())) {
            String newEmail = request.getEmail().trim().toLowerCase();
            if (userRepository.existsByEmail(newEmail)) {
                throw new DuplicateResourceException("Email is already taken: " + newEmail);
            }
            user.setEmail(newEmail);
        }

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            user.setName(request.getName().trim());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getRole() != null) {
            user.setRole(request.getRole());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        if (request.getPassword() != null && !request.getPassword().trim().isEmpty()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword().trim()));
        }

        User updated = userRepository.save(user);
        return AuthServiceImpl.mapToUserResponse(updated);
    }

    @Override
    @Transactional
    public void deleteUser(UUID id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User not found with id: " + id);
        }
        userRepository.deleteById(id);
    }
}
