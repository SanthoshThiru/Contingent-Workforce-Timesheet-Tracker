package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.common.PageResponse;
import com.contingentworkforce.dto.vendor.VendorRequest;
import com.contingentworkforce.dto.vendor.VendorResponse;
import com.contingentworkforce.entity.Vendor;
import com.contingentworkforce.enums.VendorStatus;
import com.contingentworkforce.exception.AccessDeniedException;

import com.contingentworkforce.exception.ResourceNotFoundException;
import com.contingentworkforce.repository.ContractorRepository;
import com.contingentworkforce.repository.VendorRepository;
import com.contingentworkforce.security.SecurityUtils;
import com.contingentworkforce.service.VendorService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.contingentworkforce.entity.User;
import com.contingentworkforce.enums.Role;
import com.contingentworkforce.enums.UserStatus;
import com.contingentworkforce.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class VendorServiceImpl implements VendorService {

    private final VendorRepository vendorRepository;
    private final ContractorRepository contractorRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public VendorResponse createVendor(VendorRequest request) {
        User manager = null;
        if (request.getManagerId() != null) {
            manager = userRepository.findById(request.getManagerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Manager not found with id: " + request.getManagerId()));
        } else if (SecurityUtils.isManager()) {
            manager = userRepository.findById(SecurityUtils.getCurrentUserId()).orElse(null);
        }

        Vendor vendor = Vendor.builder()
                .vendorName(request.getVendorName().trim())
                .contactPerson(request.getContactPerson())
                .email(request.getEmail() != null ? request.getEmail().toLowerCase().trim() : null)
                .phone(request.getPhone())
                .address(request.getAddress())
                .contractStartDate(request.getContractStartDate())
                .contractEndDate(request.getContractEndDate())
                .status(request.getStatus() != null ? request.getStatus() : VendorStatus.ACTIVE)
                .manager(manager)
                .build();

        Vendor saved = vendorRepository.save(vendor);

        // Create or update login credentials for the Vendor in the users table
        if (saved.getEmail() != null && !saved.getEmail().trim().isEmpty()) {
            String email = saved.getEmail().trim().toLowerCase();
            String rawPassword = (request.getPassword() != null && !request.getPassword().trim().isEmpty())
                    ? request.getPassword().trim()
                    : "Password123!";
            userRepository.findByEmail(email).map(existingUser -> {
                if (request.getPassword() != null && !request.getPassword().trim().isEmpty()) {
                    existingUser.setPasswordHash(passwordEncoder.encode(rawPassword));
                    return userRepository.save(existingUser);
                }
                return existingUser;
            }).orElseGet(() -> {
                String userName = saved.getContactPerson() != null && !saved.getContactPerson().trim().isEmpty()
                        ? saved.getContactPerson().trim()
                        : saved.getVendorName();
                User newUser = User.builder()
                        .name(userName)
                        .email(email)
                        .phone(saved.getPhone())
                        .passwordHash(passwordEncoder.encode(rawPassword))
                        .role(Role.VENDOR)
                        .status(UserStatus.ACTIVE)
                        .build();
                return userRepository.save(newUser);
            });
        }

        return mapToVendorResponse(saved);
    }

    @Override
    @Transactional
    public VendorResponse updateVendor(UUID id, VendorRequest request) {
        Vendor vendor = vendorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + id));

        // If vendor role, ensure updating own profile
        if (SecurityUtils.isVendor()) {
            String currentUserEmail = SecurityUtils.getCurrentUserEmail();
            if (vendor.getEmail() != null && !vendor.getEmail().equalsIgnoreCase(currentUserEmail)) {
                throw new AccessDeniedException("Vendors can only update their own profile");
            }
        }

        vendor.setVendorName(request.getVendorName().trim());
        vendor.setContactPerson(request.getContactPerson());
        if (request.getEmail() != null) {
            vendor.setEmail(request.getEmail().toLowerCase().trim());
        }
        vendor.setPhone(request.getPhone());
        vendor.setAddress(request.getAddress());
        if (request.getContractStartDate() != null) {
            vendor.setContractStartDate(request.getContractStartDate());
        }
        if (request.getContractEndDate() != null) {
            vendor.setContractEndDate(request.getContractEndDate());
        }
        if (request.getStatus() != null && !SecurityUtils.isVendor()) {
            vendor.setStatus(request.getStatus());
        }
        if (request.getManagerId() != null && (SecurityUtils.isAdmin() || SecurityUtils.isManager())) {
            User manager = userRepository.findById(request.getManagerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Manager not found with id: " + request.getManagerId()));
            vendor.setManager(manager);
        }

        Vendor updated = vendorRepository.save(vendor);
        return mapToVendorResponse(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public VendorResponse getVendorById(UUID id) {
        Vendor vendor = vendorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + id));
        return mapToVendorResponse(vendor);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<VendorResponse> getVendors(UUID managerId, String search, VendorStatus status, Pageable pageable) {
        UUID effectiveManagerId = managerId;
        if (SecurityUtils.isManager()) {
            effectiveManagerId = SecurityUtils.getCurrentUserId();
        }

        Page<Vendor> vendors = vendorRepository.findWithFilters(search, status, effectiveManagerId, pageable);
        return PageResponse.from(vendors.map(this::mapToVendorResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VendorResponse> getAllVendorsList() {
        if (SecurityUtils.isManager()) {
            return vendorRepository.findByManagerId(SecurityUtils.getCurrentUserId()).stream()
                    .map(this::mapToVendorResponse)
                    .collect(Collectors.toList());
        }
        return vendorRepository.findAll().stream()
                .map(this::mapToVendorResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteVendor(UUID id) {
        if (!vendorRepository.existsById(id)) {
            throw new ResourceNotFoundException("Vendor not found with id: " + id);
        }
        vendorRepository.deleteById(id);
    }

    public VendorResponse mapToVendorResponse(Vendor vendor) {
        if (vendor == null) return null;
        int count = contractorRepository.findByVendorId(vendor.getId()).size();
        return VendorResponse.builder()
                .id(vendor.getId())
                .vendorName(vendor.getVendorName())
                .contactPerson(vendor.getContactPerson())
                .email(vendor.getEmail())
                .phone(vendor.getPhone())
                .address(vendor.getAddress())
                .contractStartDate(vendor.getContractStartDate())
                .contractEndDate(vendor.getContractEndDate())
                .status(vendor.getStatus())
                .contractorCount(count)
                .managerId(vendor.getManager() != null ? vendor.getManager().getId() : null)
                .managerName(vendor.getManager() != null ? vendor.getManager().getName() : "Unassigned")
                .managerEmail(vendor.getManager() != null ? vendor.getManager().getEmail() : null)
                .createdAt(vendor.getCreatedAt())
                .updatedAt(vendor.getUpdatedAt())
                .build();
    }
}
