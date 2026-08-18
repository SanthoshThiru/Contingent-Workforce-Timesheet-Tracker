package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.common.PageResponse;
import com.contingentworkforce.dto.milestone.MilestoneRequest;
import com.contingentworkforce.dto.milestone.MilestoneResponse;
import com.contingentworkforce.entity.Milestone;
import com.contingentworkforce.entity.Project;
import com.contingentworkforce.entity.User;
import com.contingentworkforce.enums.ApprovalStatus;
import com.contingentworkforce.enums.EntityType;
import com.contingentworkforce.enums.MilestoneStatus;
import com.contingentworkforce.enums.NotificationType;
import com.contingentworkforce.exception.InvalidStateTransitionException;
import com.contingentworkforce.exception.ResourceNotFoundException;

import com.contingentworkforce.repository.MilestoneRepository;
import com.contingentworkforce.repository.ProjectRepository;
import com.contingentworkforce.repository.UserRepository;
import com.contingentworkforce.security.SecurityUtils;
import com.contingentworkforce.service.ApprovalService;
import com.contingentworkforce.service.MilestoneService;
import com.contingentworkforce.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.contingentworkforce.entity.Timesheet;
import com.contingentworkforce.entity.Contractor;
import com.contingentworkforce.entity.Vendor;
import com.contingentworkforce.entity.ProjectMember;
import com.contingentworkforce.enums.MemberStatus;
import com.contingentworkforce.repository.TimesheetRepository;
import com.contingentworkforce.repository.ContractorRepository;
import com.contingentworkforce.repository.VendorRepository;
import com.contingentworkforce.repository.ProjectMemberRepository;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MilestoneServiceImpl implements MilestoneService {

    private final MilestoneRepository milestoneRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ApprovalService approvalService;
    private final NotificationService notificationService;
    private final TimesheetRepository timesheetRepository;
    private final ContractorRepository contractorRepository;
    private final VendorRepository vendorRepository;
    private final ProjectMemberRepository projectMemberRepository;

    @Override
    @Transactional
    public MilestoneResponse createMilestone(MilestoneRequest request) {
        Project project = projectRepository.findById(request.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + request.getProjectId()));

        Integer assignedDays = request.getAssignedDays() != null && request.getAssignedDays() > 0 ? request.getAssignedDays() : 10;
        Integer pct = request.getCompletionPercentage() != null ? request.getCompletionPercentage() : 0;
        MilestoneStatus status = request.getStatus();
        if (pct >= 100) {
            status = MilestoneStatus.COMPLETED;
        } else if (status == null) {
            status = (pct > 0) ? MilestoneStatus.IN_PROGRESS : MilestoneStatus.NOT_STARTED;
        }

        Milestone milestone = Milestone.builder()
                .project(project)
                .milestoneName(request.getMilestoneName().trim())
                .description(request.getDescription())
                .startDate(request.getStartDate() != null ? request.getStartDate() : (project.getStartDate() != null ? project.getStartDate() : null))
                .dueDate(request.getDueDate())
                .assignedDays(assignedDays)
                .billingAmount(request.getBillingAmount() != null ? request.getBillingAmount() : BigDecimal.ZERO)
                .completionPercentage(pct)
                .status(status)
                .build();

        Milestone saved = milestoneRepository.save(milestone);
        return mapToMilestoneResponse(saved);
    }

    @Override
    @Transactional
    public MilestoneResponse updateMilestone(UUID id, MilestoneRequest request) {
        Milestone milestone = milestoneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone not found with id: " + id));

        milestone.setMilestoneName(request.getMilestoneName().trim());
        milestone.setDescription(request.getDescription());
        if (request.getStartDate() != null) milestone.setStartDate(request.getStartDate());
        if (request.getDueDate() != null) milestone.setDueDate(request.getDueDate());
        if (request.getAssignedDays() != null && request.getAssignedDays() > 0) {
            milestone.setAssignedDays(request.getAssignedDays());
        }
        if (request.getBillingAmount() != null) milestone.setBillingAmount(request.getBillingAmount());

        if (request.getCompletionPercentage() != null) {
            milestone.setCompletionPercentage(request.getCompletionPercentage());
            if (request.getCompletionPercentage() >= 100) {
                milestone.setStatus(MilestoneStatus.COMPLETED);
                // If newly completed, notify Project Manager
                if (milestone.getProject().getManager() != null) {
                    notificationService.createNotification(
                            milestone.getProject().getManager(),
                            "Milestone Completed",
                            String.format("Milestone '%s' for project '%s' reached 100%% completion and is ready for approval.",
                                    milestone.getMilestoneName(), milestone.getProject().getProjectName()),
                            NotificationType.MILESTONE
                    );
                }
            }
        }

        if (request.getStatus() != null && milestone.getCompletionPercentage() < 100) {
            milestone.setStatus(request.getStatus());
        }

        Milestone updated = milestoneRepository.save(milestone);
        return mapToMilestoneResponse(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public MilestoneResponse getMilestoneById(UUID id) {
        Milestone milestone = milestoneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone not found with id: " + id));
        return mapToMilestoneResponse(milestone);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MilestoneResponse> getMilestonesByProject(UUID projectId) {
        return milestoneRepository.findByProjectId(projectId).stream()
                .map(this::mapToMilestoneResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MilestoneResponse> getMilestones(UUID projectId, MilestoneStatus status, String search, Pageable pageable) {
        List<UUID> allowedProjectIds = null;

        if (SecurityUtils.isContractor()) {
            Contractor contractor = contractorRepository.findByUserId(SecurityUtils.getCurrentUserId()).orElse(null);
            if (contractor == null) {
                return PageResponse.empty();
            }
            List<ProjectMember> memberships = projectMemberRepository.findByContractorIdAndStatus(contractor.getId(), MemberStatus.ACTIVE);
            if (memberships.isEmpty()) {
                return PageResponse.empty();
            }
            allowedProjectIds = memberships.stream().map(m -> m.getProject().getId()).collect(Collectors.toList());
        } else if (SecurityUtils.isVendor()) {
            Vendor vendor = vendorRepository.findByEmail(SecurityUtils.getCurrentUserEmail()).orElse(null);
            if (vendor == null) {
                return PageResponse.empty();
            }
            List<Project> vendorProjects = projectRepository.findByVendorId(vendor.getId());
            if (vendorProjects.isEmpty()) {
                return PageResponse.empty();
            }
            allowedProjectIds = vendorProjects.stream().map(Project::getId).collect(Collectors.toList());
        } else if (SecurityUtils.isManager()) {
            List<Project> managedProjects = projectRepository.findByManagerId(SecurityUtils.getCurrentUserId());
            if (managedProjects.isEmpty()) {
                return PageResponse.empty();
            }
            allowedProjectIds = managedProjects.stream().map(Project::getId).collect(Collectors.toList());
        }

        Page<Milestone> page = milestoneRepository.findWithFiltersAndProjectIds(projectId, allowedProjectIds, status, search, pageable);
        return PageResponse.from(page.map(this::mapToMilestoneResponse));
    }

    @Override
    @Transactional
    public MilestoneResponse approveMilestone(UUID id) {
        Milestone milestone = milestoneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone not found with id: " + id));

        if (milestone.getStatus() != MilestoneStatus.COMPLETED && milestone.getCompletionPercentage() < 100) {
            throw new InvalidStateTransitionException("Only completed milestones (100% completion) can be approved");
        }

        User approver = userRepository.findById(SecurityUtils.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Approver user not found"));

        milestone.setStatus(MilestoneStatus.COMPLETED);
        milestone.setApprovedBy(approver);
        milestone.setApprovedAt(LocalDateTime.now());
        Milestone updated = milestoneRepository.save(milestone);

        // Record Approval History
        approvalService.recordApproval(
                EntityType.MILESTONE,
                milestone.getId(),
                null,
                approver,
                ApprovalStatus.APPROVED,
                "Milestone approved by " + approver.getName() + " with billing amount " + milestone.getBillingAmount()
        );

        // Notify Vendor if project is linked to a vendor
        if (milestone.getProject().getVendor() != null && milestone.getProject().getVendor().getEmail() != null) {
            userRepository.findByEmail(milestone.getProject().getVendor().getEmail()).ifPresent(vendorUser ->
                    notificationService.createNotification(
                            vendorUser,
                            "Milestone Approved",
                            String.format("Milestone '%s' for project '%s' has been approved. Billing amount: %s.",
                                    milestone.getMilestoneName(), milestone.getProject().getProjectName(), milestone.getBillingAmount()),
                            NotificationType.MILESTONE
                    )
            );
        }

        return mapToMilestoneResponse(updated);
    }

    @Override
    @Transactional
    public MilestoneResponse completeMilestone(UUID id) {
        Milestone milestone = milestoneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone not found with id: " + id));

        milestone.setStatus(MilestoneStatus.COMPLETED);
        milestone.setCompletionPercentage(100);
        Milestone updated = milestoneRepository.save(milestone);

        // Notify Project Manager that contractor accomplished the milestone
        if (milestone.getProject().getManager() != null) {
            notificationService.createNotification(
                    milestone.getProject().getManager(),
                    "Milestone Accomplished",
                    String.format("Milestone '%s' for project '%s' has been marked as accomplished by the contractor.",
                            milestone.getMilestoneName(), milestone.getProject().getProjectName()),
                    NotificationType.MILESTONE
            );
        }

        return mapToMilestoneResponse(updated);
    }

    @Override
    @Transactional
    public void deleteMilestone(UUID id) {
        if (!milestoneRepository.existsById(id)) {
            throw new ResourceNotFoundException("Milestone not found with id: " + id);
        }
        milestoneRepository.deleteById(id);
    }

    public MilestoneResponse mapToMilestoneResponse(Milestone milestone) {
        if (milestone == null) return null;

        Long distinctDays = timesheetRepository.countDistinctWorkDaysByMilestoneId(milestone.getId());
        int completedDays = distinctDays != null ? distinctDays.intValue() : 0;

        Double totalHours = timesheetRepository.sumTotalHoursByMilestoneId(milestone.getId());
        double loggedHours = totalHours != null ? totalHours : 0.0;

        List<Timesheet> timesheets = timesheetRepository.findByMilestoneId(milestone.getId());
        List<String> contributingContractors = timesheets.stream()
                .filter(t -> t.getContractor() != null && t.getContractor().getUser() != null)
                .map(t -> t.getContractor().getUser().getName())
                .distinct()
                .collect(Collectors.toList());

        int assignedDays = (milestone.getAssignedDays() != null && milestone.getAssignedDays() > 0)
                ? milestone.getAssignedDays() : 10;

        int completionPercentage;
        if (milestone.getStatus() == MilestoneStatus.COMPLETED) {
            if (completedDays == 0) {
                completedDays = assignedDays;
            }
            completionPercentage = 100;
        } else {
            completionPercentage = (int) Math.min(100, Math.round(((double) completedDays / (double) assignedDays) * 100));
        }

        return MilestoneResponse.builder()
                .id(milestone.getId())
                .projectId(milestone.getProject().getId())
                .projectName(milestone.getProject().getProjectName())
                .milestoneName(milestone.getMilestoneName())
                .description(milestone.getDescription())
                .startDate(milestone.getStartDate())
                .dueDate(milestone.getDueDate())
                .assignedDays(assignedDays)
                .completedDays(completedDays)
                .loggedHours(loggedHours)
                .timesheetsCount(timesheets.size())
                .contributingContractors(contributingContractors)
                .billingAmount(milestone.getBillingAmount())
                .completionPercentage(completionPercentage)
                .status(milestone.getStatus())
                .approvedBy(milestone.getApprovedBy() != null ? AuthServiceImpl.mapToUserResponse(milestone.getApprovedBy()) : null)
                .approvedAt(milestone.getApprovedAt())
                .createdAt(milestone.getCreatedAt())
                .updatedAt(milestone.getUpdatedAt())
                .build();
    }
}
