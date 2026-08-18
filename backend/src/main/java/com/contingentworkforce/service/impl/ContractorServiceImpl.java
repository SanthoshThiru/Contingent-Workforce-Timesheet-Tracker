package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.common.PageResponse;
import com.contingentworkforce.dto.contractor.ContractorRequest;
import com.contingentworkforce.dto.contractor.ContractorResponse;
import com.contingentworkforce.entity.Contractor;
import com.contingentworkforce.entity.User;
import com.contingentworkforce.entity.Vendor;
import com.contingentworkforce.enums.ContractorStatus;
import com.contingentworkforce.exception.AccessDeniedException;

import com.contingentworkforce.exception.DuplicateResourceException;
import com.contingentworkforce.exception.ResourceNotFoundException;
import com.contingentworkforce.repository.ContractorRepository;
import com.contingentworkforce.repository.UserRepository;
import com.contingentworkforce.repository.VendorRepository;
import com.contingentworkforce.security.SecurityUtils;
import com.contingentworkforce.service.ContractorService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.contingentworkforce.enums.Role;
import com.contingentworkforce.enums.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class ContractorServiceImpl implements ContractorService {

    private final ContractorRepository contractorRepository;
    private final UserRepository userRepository;
    private final VendorRepository vendorRepository;
    private final VendorServiceImpl vendorService;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public ContractorResponse createContractor(ContractorRequest request) {
        User user;
        if (request.getUserId() != null) {
            user = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + request.getUserId()));
        } else if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            String email = request.getEmail().trim().toLowerCase();
            String rawPassword = (request.getPassword() != null && !request.getPassword().trim().isEmpty())
                    ? request.getPassword().trim()
                    : "Password123!";
            user = userRepository.findByEmail(email).map(existingUser -> {
                if (request.getPassword() != null && !request.getPassword().trim().isEmpty()) {
                    existingUser.setPasswordHash(passwordEncoder.encode(rawPassword));
                    return userRepository.save(existingUser);
                }
                return existingUser;
            }).orElseGet(() -> {
                String userName = request.getName() != null && !request.getName().trim().isEmpty()
                        ? request.getName().trim()
                        : "Contractor User";
                User newUser = User.builder()
                        .name(userName)
                        .email(email)
                        .phone(request.getPhone())
                        .passwordHash(passwordEncoder.encode(rawPassword))
                        .role(Role.CONTRACTOR)
                        .status(UserStatus.ACTIVE)
                        .build();
                return userRepository.save(newUser);
            });
        } else {
            throw new IllegalArgumentException("Either userId or contractor email must be provided");
        }

        if (contractorRepository.findByUserId(user.getId()).isPresent()) {
            throw new DuplicateResourceException("A contractor profile already exists for user: " + user.getEmail());
        }

        Vendor vendor;
        if (SecurityUtils.isVendor()) {
            String currentUserEmail = SecurityUtils.getCurrentUserEmail();
            Vendor currentVendor = vendorRepository.findByEmail(currentUserEmail).orElse(null);
            if (currentVendor != null) {
                vendor = currentVendor;
            } else if (request.getVendorId() != null) {
                vendor = vendorRepository.findById(request.getVendorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + request.getVendorId()));
            } else {
                throw new ResourceNotFoundException("Vendor profile not found for logged-in user: " + currentUserEmail);
            }
        } else {
            if (request.getVendorId() == null) {
                throw new IllegalArgumentException("Vendor ID is required");
            }
            vendor = vendorRepository.findById(request.getVendorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + request.getVendorId()));
        }

        Contractor contractor = Contractor.builder()
                .user(user)
                .vendor(vendor)
                .jobRole(request.getJobRole().trim())
                .hourlyRate(request.getHourlyRate())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .status(request.getStatus() != null ? request.getStatus() : ContractorStatus.ACTIVE)
                .build();

        Contractor saved = contractorRepository.save(contractor);
        return mapToContractorResponse(saved);
    }

    @Override
    @Transactional
    public ContractorResponse updateContractor(UUID id, ContractorRequest request) {
        Contractor contractor = contractorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Contractor not found with id: " + id));

        if (SecurityUtils.isVendor()) {
            String currentUserEmail = SecurityUtils.getCurrentUserEmail();
            if (contractor.getVendor().getEmail() != null && !contractor.getVendor().getEmail().equalsIgnoreCase(currentUserEmail)) {
                throw new AccessDeniedException("Vendors can only update their own contractors");
            }
        }

        if (request.getVendorId() != null && !request.getVendorId().equals(contractor.getVendor().getId())) {
            Vendor vendor = vendorRepository.findById(request.getVendorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + request.getVendorId()));
            contractor.setVendor(vendor);
        }

        contractor.setJobRole(request.getJobRole().trim());
        contractor.setHourlyRate(request.getHourlyRate());
        if (request.getStartDate() != null) contractor.setStartDate(request.getStartDate());
        if (request.getEndDate() != null) contractor.setEndDate(request.getEndDate());
        if (request.getStatus() != null) contractor.setStatus(request.getStatus());

        Contractor updated = contractorRepository.save(contractor);
        return mapToContractorResponse(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractorResponse getContractorById(UUID id) {
        Contractor contractor = contractorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Contractor not found with id: " + id));
        return mapToContractorResponse(contractor);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractorResponse getContractorByUserId(UUID userId) {
        Contractor contractor = contractorRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Contractor profile not found for user: " + userId));
        return mapToContractorResponse(contractor);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ContractorResponse> getContractors(UUID vendorId, ContractorStatus status, String search, Pageable pageable) {
        UUID effectiveVendorId = vendorId;
        if (SecurityUtils.isVendor()) {
            Vendor currentVendor = vendorRepository.findByEmail(SecurityUtils.getCurrentUserEmail())
                    .orElse(null);
            if (currentVendor != null) {
                effectiveVendorId = currentVendor.getId();
            } else {
                return PageResponse.empty();
            }
        }

        Page<Contractor> page = contractorRepository.findWithFilters(effectiveVendorId, status, search, pageable);
        return PageResponse.from(page.map(this::mapToContractorResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractorResponse> getContractorsByVendor(UUID vendorId) {
        return contractorRepository.findByVendorId(vendorId).stream()
                .map(this::mapToContractorResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteContractor(UUID id) {
        if (!contractorRepository.existsById(id)) {
            throw new ResourceNotFoundException("Contractor not found with id: " + id);
        }
        contractorRepository.deleteById(id);
    }

    public ContractorResponse mapToContractorResponse(Contractor contractor) {
        if (contractor == null) return null;
        return ContractorResponse.builder()
                .id(contractor.getId())
                .user(AuthServiceImpl.mapToUserResponse(contractor.getUser()))
                .vendor(vendorService.mapToVendorResponse(contractor.getVendor()))
                .jobRole(contractor.getJobRole())
                .hourlyRate(contractor.getHourlyRate())
                .startDate(contractor.getStartDate())
                .endDate(contractor.getEndDate())
                .status(contractor.getStatus())
                .createdAt(contractor.getCreatedAt())
                .updatedAt(contractor.getUpdatedAt())
                .build();
    }
}
