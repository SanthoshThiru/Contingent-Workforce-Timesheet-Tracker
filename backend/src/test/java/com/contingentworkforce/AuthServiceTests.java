package com.contingentworkforce;

import com.contingentworkforce.dto.auth.AuthResponse;
import com.contingentworkforce.dto.auth.LoginRequest;
import com.contingentworkforce.dto.auth.RegisterRequest;
import com.contingentworkforce.dto.auth.UserResponse;
import com.contingentworkforce.entity.User;
import com.contingentworkforce.enums.Role;
import com.contingentworkforce.enums.UserStatus;
import com.contingentworkforce.exception.DuplicateResourceException;
import com.contingentworkforce.repository.UserRepository;
import com.contingentworkforce.security.CustomUserDetails;
import com.contingentworkforce.security.JwtService;
import com.contingentworkforce.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class AuthServiceTests {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthServiceImpl authService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
                .id(UUID.randomUUID())
                .name("Alex Manager")
                .email("manager@example.com")
                .passwordHash("hashed_pwd")
                .role(Role.MANAGER)
                .status(UserStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Should successfully register a new user")
    void testRegisterSuccess() {
        RegisterRequest request = RegisterRequest.builder()
                .name("Alex Manager")
                .email("manager@example.com")
                .password("Password123!")
                .role(Role.MANAGER)
                .phone("+123456789")
                .build();

        when(userRepository.existsByEmail("manager@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed_pwd");
        when(userRepository.save(any(User.class))).thenReturn(sampleUser);

        UserResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals("manager@example.com", response.getEmail());
        assertEquals(Role.MANAGER, response.getRole());
    }

    @Test
    @DisplayName("Should throw DuplicateResourceException when email is already registered")
    void testRegisterDuplicateEmail() {
        RegisterRequest request = RegisterRequest.builder()
                .name("Alex Manager")
                .email("manager@example.com")
                .password("Password123!")
                .role(Role.MANAGER)
                .build();

        when(userRepository.existsByEmail("manager@example.com")).thenReturn(true);

        assertThrows(DuplicateResourceException.class, () -> authService.register(request));
    }

    @Test
    @DisplayName("Should login and return JWT token")
    void testLoginSuccess() {
        LoginRequest request = LoginRequest.builder()
                .email("manager@example.com")
                .password("Password123!")
                .build();

        CustomUserDetails userDetails = new CustomUserDetails(sampleUser);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(jwtService.generateToken(any(CustomUserDetails.class))).thenReturn("sample.jwt.token");
        when(jwtService.getExpirationMs()).thenReturn(86400000L);
        when(userRepository.findByEmail("manager@example.com")).thenReturn(Optional.of(sampleUser));

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("sample.jwt.token", response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("manager@example.com", response.getUser().getEmail());
    }
}
