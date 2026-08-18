package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.common.PageResponse;
import com.contingentworkforce.dto.project.*;
import com.contingentworkforce.entity.*;
import com.contingentworkforce.enums.MemberStatus;
import com.contingentworkforce.enums.ProjectStatus;
import com.contingentworkforce.exception.AccessDeniedException;
import com.contingentworkforce.exception.DuplicateResourceException;
import com.contingentworkforce.exception.ResourceNotFoundException;

import com.contingentworkforce.repository.*;
import com.contingentworkforce.security.SecurityUtils;
import com.contingentworkforce.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final VendorRepository vendorRepository;
    private final UserRepository userRepository;
    private final ContractorRepository contractorRepository;
    private final VendorServiceImpl vendorService;
    private final ContractorServiceImpl contractorService;

    @Override
    @Transactional
    public ProjectResponse createProject(ProjectRequest request) {
        Vendor vendor = null;
        if (request.getVendorId() != null) {
            vendor = vendorRepository.findById(request.getVendorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + request.getVendorId()));
        }

        User manager = null;
        if (request.getManagerId() != null) {
            manager = userRepository.findById(request.getManagerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Manager user not found with id: " + request.getManagerId()));
        } else if (SecurityUtils.isManager()) {
            manager = userRepository.findById(SecurityUtils.getCurrentUserId()).orElse(null);
        }

        Project project = Project.builder()
                .projectName(request.getProjectName().trim())
                .clientName(request.getClientName())
                .description(request.getDescription())
                .vendor(vendor)
                .manager(manager)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .budget(request.getBudget())
                .status(request.getStatus() != null ? request.getStatus() : ProjectStatus.PLANNING)
                .build();

        Project saved = projectRepository.save(project);
        return mapToProjectResponse(saved);
    }

    @Override
    @Transactional
    public ProjectResponse updateProject(UUID id, ProjectRequest request) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));

        if (request.getVendorId() != null) {
            Vendor vendor = vendorRepository.findById(request.getVendorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + request.getVendorId()));
            project.setVendor(vendor);
        }

        if (request.getManagerId() != null) {
            User manager = userRepository.findById(request.getManagerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Manager not found with id: " + request.getManagerId()));
            project.setManager(manager);
        }

        project.setProjectName(request.getProjectName().trim());
        project.setClientName(request.getClientName());
        project.setDescription(request.getDescription());
        if (request.getStartDate() != null) project.setStartDate(request.getStartDate());
        if (request.getEndDate() != null) project.setEndDate(request.getEndDate());
        if (request.getBudget() != null) project.setBudget(request.getBudget());
        if (request.getStatus() != null) project.setStatus(request.getStatus());

        Project updated = projectRepository.save(project);
        return mapToProjectResponse(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectResponse getProjectById(UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));
        return mapToProjectResponse(project);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> getProjects(UUID vendorId, UUID managerId, ProjectStatus status, String search, Pageable pageable) {
        UUID effectiveVendorId = vendorId;
        UUID effectiveManagerId = managerId;

        if (SecurityUtils.isVendor()) {
            Vendor currentVendor = vendorRepository.findByEmail(SecurityUtils.getCurrentUserEmail()).orElse(null);
            if (currentVendor != null) {
                effectiveVendorId = currentVendor.getId();
            } else {
                return PageResponse.empty();
            }
        } else if (SecurityUtils.isManager()) {
            effectiveManagerId = SecurityUtils.getCurrentUserId();
        } else if (SecurityUtils.isContractor()) {
            Contractor contractor = contractorRepository.findByUserId(SecurityUtils.getCurrentUserId()).orElse(null);
            if (contractor == null) {
                return PageResponse.empty();
            }
            List<ProjectMember> memberships = projectMemberRepository.findByContractorIdAndStatus(contractor.getId(), MemberStatus.ACTIVE);
            if (memberships.isEmpty()) {
                return PageResponse.empty();
            }
            List<ProjectResponse> allottedList = memberships.stream()
                    .map(m -> mapToProjectResponse(m.getProject()))
                    .filter(p -> status == null || p.getStatus() == status)
                    .filter(p -> search == null || p.getProjectName().toLowerCase().contains(search.toLowerCase()))
                    .collect(Collectors.toList());
            return PageResponse.<ProjectResponse>builder()
                    .content(allottedList)
                    .pageNumber(0)
                    .pageSize(Math.max(1, allottedList.size()))
                    .totalElements(allottedList.size())
                    .totalPages(1)
                    .last(true)
                    .build();
        }

        Page<Project> page = projectRepository.findWithFilters(effectiveVendorId, effectiveManagerId, status, search, pageable);
        return PageResponse.from(page.map(this::mapToProjectResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectResponse> getProjectsForCurrentUser() {
        if (SecurityUtils.isAdmin()) {
            return projectRepository.findAll().stream().map(this::mapToProjectResponse).collect(Collectors.toList());
        }
        if (SecurityUtils.isManager()) {
            return projectRepository.findByManagerId(SecurityUtils.getCurrentUserId()).stream()
                    .map(this::mapToProjectResponse).collect(Collectors.toList());
        }
        if (SecurityUtils.isVendor()) {
            Vendor vendor = vendorRepository.findByEmail(SecurityUtils.getCurrentUserEmail()).orElse(null);
            if (vendor == null) return List.of();
            return projectRepository.findByVendorId(vendor.getId()).stream()
                    .map(this::mapToProjectResponse).collect(Collectors.toList());
        }
        if (SecurityUtils.isContractor()) {
            Contractor contractor = contractorRepository.findByUserId(SecurityUtils.getCurrentUserId()).orElse(null);
            if (contractor == null) return List.of();
            List<ProjectMember> memberships = projectMemberRepository.findByContractorIdAndStatus(contractor.getId(), MemberStatus.ACTIVE);
            return memberships.stream().map(m -> mapToProjectResponse(m.getProject())).collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    @Transactional
    public void deleteProject(UUID id) {
        if (!projectRepository.existsById(id)) {
            throw new ResourceNotFoundException("Project not found with id: " + id);
        }
        projectRepository.deleteById(id);
    }

    @Override
    @Transactional
    public ProjectMemberResponse assignContractor(UUID projectId, ProjectMemberRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

        Contractor contractor = contractorRepository.findById(request.getContractorId())
                .orElseThrow(() -> new ResourceNotFoundException("Contractor not found with id: " + request.getContractorId()));

        if (SecurityUtils.isVendor()) {
            Vendor vendor = vendorRepository.findByEmail(SecurityUtils.getCurrentUserEmail()).orElse(null);
            if (vendor != null) {
                if (project.getVendor() != null && !project.getVendor().getId().equals(vendor.getId())) {
                    throw new AccessDeniedException("Vendors can only assign contractors to their own vendor-mapped projects");
                }
                if (contractor.getVendor() != null && !contractor.getVendor().getId().equals(vendor.getId())) {
                    throw new AccessDeniedException("Vendors can only assign contractors mapped to their organization");
                }
            }
        }

        if (projectMemberRepository.existsByProjectIdAndContractorId(projectId, request.getContractorId())) {
            throw new DuplicateResourceException("Contractor is already assigned to this project");
        }


        ProjectMember member = ProjectMember.builder()
                .project(project)
                .contractor(contractor)
                .assignedDate(request.getAssignedDate() != null ? request.getAssignedDate() : LocalDate.now())
                .endDate(request.getEndDate())
                .status(request.getStatus() != null ? request.getStatus() : MemberStatus.ACTIVE)
                .build();

        ProjectMember saved = projectMemberRepository.save(member);
        return mapToProjectMemberResponse(saved);
    }

    @Override
    @Transactional
    public void removeContractor(UUID projectId, UUID contractorId) {
        ProjectMember member = projectMemberRepository.findByProjectIdAndContractorId(projectId, contractorId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment not found for project and contractor"));
        projectMemberRepository.delete(member);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> getProjectMembers(UUID projectId) {
        return projectMemberRepository.findByProjectId(projectId).stream()
                .map(this::mapToProjectMemberResponse)
                .collect(Collectors.toList());
    }

    public ProjectResponse mapToProjectResponse(Project project) {
        if (project == null) return null;
        List<ProjectMemberResponse> members = projectMemberRepository.findByProjectId(project.getId()).stream()
                .map(this::mapToProjectMemberResponse)
                .collect(Collectors.toList());

        return ProjectResponse.builder()
                .id(project.getId())
                .projectName(project.getProjectName())
                .clientName(project.getClientName())
                .description(project.getDescription())
                .vendor(project.getVendor() != null ? vendorService.mapToVendorResponse(project.getVendor()) : null)
                .manager(project.getManager() != null ? AuthServiceImpl.mapToUserResponse(project.getManager()) : null)
                .startDate(project.getStartDate())
                .endDate(project.getEndDate())
                .budget(project.getBudget())
                .status(project.getStatus())
                .members(members)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    public ProjectMemberResponse mapToProjectMemberResponse(ProjectMember member) {
        if (member == null) return null;
        return ProjectMemberResponse.builder()
                .id(member.getId())
                .projectId(member.getProject().getId())
                .contractor(contractorService.mapToContractorResponse(member.getContractor()))
                .assignedDate(member.getAssignedDate())
                .endDate(member.getEndDate())
                .status(member.getStatus())
                .build();
    }
}
