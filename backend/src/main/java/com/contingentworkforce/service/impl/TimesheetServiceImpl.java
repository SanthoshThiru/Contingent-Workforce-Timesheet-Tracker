package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.common.PageResponse;
import com.contingentworkforce.dto.timesheet.TimesheetRejectRequest;
import com.contingentworkforce.dto.timesheet.TimesheetRequest;
import com.contingentworkforce.dto.timesheet.TimesheetResponse;
import com.contingentworkforce.entity.*;
import com.contingentworkforce.enums.ApprovalStatus;
import com.contingentworkforce.enums.EntityType;
import com.contingentworkforce.enums.MilestoneStatus;
import com.contingentworkforce.enums.NotificationType;
import com.contingentworkforce.enums.TimesheetStatus;
import com.contingentworkforce.exception.AccessDeniedException;
import com.contingentworkforce.exception.BadRequestException;
import com.contingentworkforce.exception.DuplicateResourceException;
import com.contingentworkforce.exception.InvalidStateTransitionException;
import com.contingentworkforce.exception.ResourceNotFoundException;
import com.contingentworkforce.repository.*;
import com.contingentworkforce.security.SecurityUtils;
import com.contingentworkforce.service.ApprovalService;
import com.contingentworkforce.service.NotificationService;
import com.contingentworkforce.service.TimesheetService;
import com.contingentworkforce.service.validation.TimesheetValidationEngine;
import com.contingentworkforce.dto.validation.ValidationResult;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class TimesheetServiceImpl implements TimesheetService {

    private final TimesheetRepository timesheetRepository;
    private final ContractorRepository contractorRepository;
    private final ProjectRepository projectRepository;
    private final MilestoneRepository milestoneRepository;
    private final UserRepository userRepository;
    private final VendorRepository vendorRepository;
    private final ContractorServiceImpl contractorService;
    private final ApprovalService approvalService;
    private final NotificationService notificationService;
    private final TimesheetValidationEngine validationEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public TimesheetResponse createTimesheet(TimesheetRequest request) {
        Contractor contractor = resolveContractor(request.getContractorId());
        Project project = projectRepository.findById(request.getProjectId())
                .orElseThrow(
                        () -> new ResourceNotFoundException("Project not found with id: " + request.getProjectId()));

        // Check duplicate entry
        if (timesheetRepository
                .findByContractorIdAndProjectIdAndWorkDate(contractor.getId(), project.getId(), request.getWorkDate())
                .isPresent()) {
            throw new DuplicateResourceException("A timesheet entry already exists for contractor "
                    + contractor.getUser().getName() + " on date " + request.getWorkDate());
        }

        BigDecimal totalHours = calculateAndValidateHours(request.getStartTime(), request.getEndTime(),
                request.getBreakHours());

        Milestone milestone = null;
        if (request.getMilestoneId() != null) {
            milestone = milestoneRepository.findById(request.getMilestoneId()).orElse(null);
        }

        Timesheet timesheet = Timesheet.builder()
                .contractor(contractor)
                .project(project)
                .milestone(milestone)
                .workDate(request.getWorkDate())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .breakHours(request.getBreakHours() != null ? request.getBreakHours() : BigDecimal.ZERO)
                .totalHours(totalHours)
                .description(request.getDescription())
                .status(TimesheetStatus.DRAFT)
                .build();

        Timesheet saved = timesheetRepository.save(timesheet);
        return mapToTimesheetResponse(saved);
    }

    @Override
    @Transactional
    public TimesheetResponse updateTimesheet(UUID id, TimesheetRequest request) {
        Timesheet timesheet = timesheetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found with id: " + id));

        // Only allow edits if in DRAFT or REJECTED status
        if (timesheet.getStatus() != TimesheetStatus.DRAFT && timesheet.getStatus() != TimesheetStatus.REJECTED) {
            throw new InvalidStateTransitionException("Only timesheets in DRAFT or REJECTED status can be edited");
        }

        // Authorization check for contractors
        if (SecurityUtils.isContractor()) {
            Contractor currentContractor = contractorRepository.findByUserId(SecurityUtils.getCurrentUserId())
                    .orElseThrow(() -> new AccessDeniedException("Contractor profile not found"));
            if (!timesheet.getContractor().getId().equals(currentContractor.getId())) {
                throw new AccessDeniedException("Contractors can only edit their own timesheets");
            }
        }

        if (request.getProjectId() != null && !request.getProjectId().equals(timesheet.getProject().getId())) {
            Project project = projectRepository.findById(request.getProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Project not found with id: " + request.getProjectId()));
            timesheet.setProject(project);
        }

        if (request.getWorkDate() != null) {
            // If date changed, check duplicates
            if (!request.getWorkDate().equals(timesheet.getWorkDate())) {
                Optional<Timesheet> duplicate = timesheetRepository.findByContractorIdAndProjectIdAndWorkDate(
                        timesheet.getContractor().getId(), timesheet.getProject().getId(), request.getWorkDate());
                if (duplicate.isPresent()) {
                    throw new DuplicateResourceException("A timesheet already exists for this date");
                }
                timesheet.setWorkDate(request.getWorkDate());
            }
        }

        LocalTime start = request.getStartTime() != null ? request.getStartTime() : timesheet.getStartTime();
        LocalTime end = request.getEndTime() != null ? request.getEndTime() : timesheet.getEndTime();
        BigDecimal breakH = request.getBreakHours() != null ? request.getBreakHours() : timesheet.getBreakHours();

        BigDecimal totalHours = calculateAndValidateHours(start, end, breakH);

        timesheet.setStartTime(start);
        timesheet.setEndTime(end);
        timesheet.setBreakHours(breakH);
        timesheet.setTotalHours(totalHours);
        timesheet.setDescription(request.getDescription());
        if (request.getMilestoneId() != null) {
            Milestone milestone = milestoneRepository.findById(request.getMilestoneId()).orElse(null);
            timesheet.setMilestone(milestone);
        }
        // If it was rejected, editing moves it back to DRAFT
        if (timesheet.getStatus() == TimesheetStatus.REJECTED) {
            timesheet.setStatus(TimesheetStatus.DRAFT);
            timesheet.setRejectionReason(null);
        }

        Timesheet updated = timesheetRepository.save(timesheet);
        return mapToTimesheetResponse(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public TimesheetResponse getTimesheetById(UUID id) {
        Timesheet timesheet = timesheetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found with id: " + id));
        return mapToTimesheetResponse(timesheet);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TimesheetResponse> getTimesheets(UUID contractorId, UUID vendorId, UUID projectId,
            TimesheetStatus status, LocalDate startDate, LocalDate endDate, Pageable pageable) {
        UUID effectiveContractorId = contractorId;
        UUID effectiveVendorId = vendorId;

        if (SecurityUtils.isContractor()) {
            Contractor contractor = contractorRepository.findByUserId(SecurityUtils.getCurrentUserId()).orElse(null);
            if (contractor != null) {
                effectiveContractorId = contractor.getId();
            } else {
                return PageResponse.empty();
            }
        } else if (SecurityUtils.isVendor()) {
            Vendor vendor = vendorRepository.findByEmail(SecurityUtils.getCurrentUserEmail()).orElse(null);
            if (vendor != null) {
                effectiveVendorId = vendor.getId();
            } else {
                return PageResponse.empty();
            }
        }

        Page<Timesheet> page = timesheetRepository.findWithFilters(effectiveContractorId, effectiveVendorId, projectId,
                status, startDate, endDate, pageable);
        return PageResponse.from(page.map(this::mapToTimesheetResponse));
    }

    @Override
    @Transactional
    public TimesheetResponse submitTimesheet(UUID id) {
        Timesheet timesheet = timesheetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found with id: " + id));

        if (timesheet.getStatus() != TimesheetStatus.DRAFT && timesheet.getStatus() != TimesheetStatus.REJECTED) {
            throw new InvalidStateTransitionException(
                    "Only DRAFT or REJECTED timesheets can be submitted. Current status: " + timesheet.getStatus());
        }

        ValidationResult validationResult = validationEngine.validate(timesheet);
        if ("BLOCKED".equals(validationResult.getStatus())) {
            throw new BadRequestException("Timesheet validation blocked submission due to critical rule violations.");
        }

        timesheet.setRiskScore(validationResult.getRiskScore());
        timesheet.setRiskLevel(validationResult.getRiskLevel());
        try {
            timesheet.setRiskReasons(objectMapper.writeValueAsString(validationResult.getRulesTriggered()));
        } catch (Exception e) {
            timesheet.setRiskReasons("[]");
        }

        timesheet.setStatus(TimesheetStatus.SUBMITTED);
        timesheet.setSubmittedAt(LocalDateTime.now());
        timesheet.setRejectionReason(null);
        Timesheet updated = timesheetRepository.save(timesheet);

        // Record approval history
        User currentUser = userRepository.findById(SecurityUtils.getCurrentUserId()).orElse(null);
        approvalService.recordApproval(EntityType.TIMESHEET, timesheet.getId(), currentUser, null,
                ApprovalStatus.PENDING, "Timesheet submitted for approval");

        // Notify Vendor Partner
        if (timesheet.getContractor().getVendor() != null && timesheet.getContractor().getVendor().getEmail() != null) {
            userRepository.findByEmail(timesheet.getContractor().getVendor().getEmail()).ifPresent(vendorUser -> {
                notificationService.createNotification(
                        vendorUser,
                        "Contractor Timesheet Submitted",
                        String.format("%s submitted a timesheet for project '%s' on %s (%.2f hours) requiring your vendor approval.",
                                timesheet.getContractor().getUser().getName(),
                                timesheet.getProject().getProjectName(),
                                timesheet.getWorkDate(),
                                timesheet.getTotalHours()),
                        NotificationType.TIMESHEET);
            });
        }

        return mapToTimesheetResponse(updated);
    }

    @Override
    @Transactional
    public TimesheetResponse approveTimesheet(UUID id) {
        Timesheet timesheet = timesheetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found with id: " + id));

        if (timesheet.getStatus() != TimesheetStatus.SUBMITTED) {
            throw new InvalidStateTransitionException(
                    "Only SUBMITTED timesheets can be approved. Current status: " + timesheet.getStatus());
        }

        if (SecurityUtils.isVendor()) {
            Vendor currentVendor = vendorRepository.findByEmail(SecurityUtils.getCurrentUserEmail()).orElse(null);
            if (currentVendor == null || timesheet.getContractor().getVendor() == null ||
                    !currentVendor.getId().equals(timesheet.getContractor().getVendor().getId())) {
                throw new AccessDeniedException("Vendors can only approve timesheets for their own contractors");
            }
        }

        User approver = userRepository.findById(SecurityUtils.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Approver user not found"));

        timesheet.setStatus(TimesheetStatus.APPROVED);
        timesheet.setApprovedAt(LocalDateTime.now());
        timesheet.setApprovedBy(approver);
        Timesheet updated = timesheetRepository.save(timesheet);

        // If timesheet specifies a milestone, mark that milestone as COMPLETED upon timesheet approval
        if (timesheet.getMilestone() != null) {
            Milestone milestone = timesheet.getMilestone();
            milestone.setStatus(MilestoneStatus.COMPLETED);
            milestone.setCompletionPercentage(100);
            milestone.setApprovedBy(approver);
            milestone.setApprovedAt(LocalDateTime.now());
            milestoneRepository.save(milestone);

            // Notify Project Manager that milestone has been marked as completed via approved timesheet
            if (milestone.getProject() != null && milestone.getProject().getManager() != null) {
                notificationService.createNotification(
                        milestone.getProject().getManager(),
                        "Milestone Completed via Approved Timesheet",
                        String.format("Milestone '%s' for project '%s' has been marked as COMPLETED following vendor approval of timesheet on %s.",
                                milestone.getMilestoneName(), milestone.getProject().getProjectName(), timesheet.getWorkDate()),
                        NotificationType.MILESTONE);
            }
        }

        // Record Approval History
        approvalService.recordApproval(EntityType.TIMESHEET, timesheet.getId(), timesheet.getContractor().getUser(),
                approver, ApprovalStatus.APPROVED, "Timesheet approved by " + approver.getName());

        // Notify Contractor
        notificationService.createNotification(
                timesheet.getContractor().getUser(),
                "Timesheet Approved",
                String.format("Your timesheet for %s on project '%s' has been approved by vendor %s.",
                        timesheet.getWorkDate(),
                        timesheet.getProject().getProjectName(),
                        approver.getName()),
                NotificationType.TIMESHEET);

        return mapToTimesheetResponse(updated);
    }

    @Override
    @Transactional
    public TimesheetResponse rejectTimesheet(UUID id, TimesheetRejectRequest rejectRequest) {
        Timesheet timesheet = timesheetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found with id: " + id));

        if (timesheet.getStatus() != TimesheetStatus.SUBMITTED) {
            throw new InvalidStateTransitionException(
                    "Only SUBMITTED timesheets can be rejected. Current status: " + timesheet.getStatus());
        }

        if (SecurityUtils.isVendor()) {
            Vendor currentVendor = vendorRepository.findByEmail(SecurityUtils.getCurrentUserEmail()).orElse(null);
            if (currentVendor == null || timesheet.getContractor().getVendor() == null ||
                    !currentVendor.getId().equals(timesheet.getContractor().getVendor().getId())) {
                throw new AccessDeniedException("Vendors can only reject timesheets for their own contractors");
            }
        }

        User approver = userRepository.findById(SecurityUtils.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Approver user not found"));

        String reason = rejectRequest.getReason() != null ? rejectRequest.getReason().trim() : "No reason provided";
        timesheet.setStatus(TimesheetStatus.REJECTED);
        timesheet.setRejectionReason(reason);
        Timesheet updated = timesheetRepository.save(timesheet);

        // Record Approval History
        approvalService.recordApproval(EntityType.TIMESHEET, timesheet.getId(), timesheet.getContractor().getUser(),
                approver, ApprovalStatus.REJECTED, "Timesheet rejected: " + reason);

        // Notify Contractor
        notificationService.createNotification(
                timesheet.getContractor().getUser(),
                "Timesheet Rejected",
                String.format("Your timesheet for %s on project '%s' was rejected. Reason: %s",
                        timesheet.getWorkDate(),
                        timesheet.getProject().getProjectName(),
                        reason),
                NotificationType.TIMESHEET);

        return mapToTimesheetResponse(updated);
    }

    @Override
    @Transactional
    public void deleteTimesheet(UUID id) {
        Timesheet timesheet = timesheetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found with id: " + id));

        if (timesheet.getStatus() != TimesheetStatus.DRAFT) {
            throw new InvalidStateTransitionException("Only DRAFT timesheets can be deleted");
        }
        timesheetRepository.delete(timesheet);
    }

    private Contractor resolveContractor(UUID requestContractorId) {
        if (requestContractorId != null) {
            return contractorRepository.findById(requestContractorId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Contractor not found with id: " + requestContractorId));
        }
        if (SecurityUtils.isContractor()) {
            return contractorRepository.findByUserId(SecurityUtils.getCurrentUserId())
                    .orElseThrow(
                            () -> new ResourceNotFoundException("Contractor profile not found for logged in user"));
        }
        throw new BadRequestException("Contractor ID is required");
    }

    private BigDecimal calculateAndValidateHours(LocalTime startTime, LocalTime endTime, BigDecimal breakHours) {
        if (startTime == null || endTime == null) {
            throw new BadRequestException("Start time and end time are required");
        }

        if (!endTime.isAfter(startTime)) {
            throw new BadRequestException("End time must be strictly after start time");
        }

        BigDecimal breakH = breakHours != null ? breakHours : BigDecimal.ZERO;
        if (breakH.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Break hours cannot be negative");
        }

        long minutes = Duration.between(startTime, endTime).toMinutes();
        BigDecimal durationHours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

        if (breakH.compareTo(durationHours) >= 0) {
            throw new BadRequestException("Break hours (" + breakH + ") cannot be equal to or exceed working duration ("
                    + durationHours + " hrs)");
        }

        BigDecimal totalHours = durationHours.subtract(breakH).setScale(2, RoundingMode.HALF_UP);

        if (totalHours.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Calculated total working hours must be greater than zero");
        }

        if (totalHours.compareTo(BigDecimal.valueOf(24)) > 0) {
            throw new BadRequestException("Total working hours cannot exceed 24 hours per day");
        }

        return totalHours;
    }

    public TimesheetResponse mapToTimesheetResponse(Timesheet timesheet) {
        if (timesheet == null)
            return null;
        return TimesheetResponse.builder()
                .id(timesheet.getId())
                .contractor(contractorService.mapToContractorResponse(timesheet.getContractor()))
                .projectId(timesheet.getProject().getId())
                .projectName(timesheet.getProject().getProjectName())
                .milestoneId(timesheet.getMilestone() != null ? timesheet.getMilestone().getId() : null)
                .milestoneName(timesheet.getMilestone() != null ? timesheet.getMilestone().getMilestoneName() : null)
                .workDate(timesheet.getWorkDate())
                .startTime(timesheet.getStartTime())
                .endTime(timesheet.getEndTime())
                .breakHours(timesheet.getBreakHours())
                .totalHours(timesheet.getTotalHours())
                .description(timesheet.getDescription())
                .status(timesheet.getStatus())
                .rejectionReason(timesheet.getRejectionReason())
                .riskScore(timesheet.getRiskScore())
                .riskLevel(timesheet.getRiskLevel())
                .riskReasons(timesheet.getRiskReasons())
                .submittedAt(timesheet.getSubmittedAt())
                .approvedAt(timesheet.getApprovedAt())
                .approvedBy(
                        timesheet.getApprovedBy() != null ? AuthServiceImpl.mapToUserResponse(timesheet.getApprovedBy())
                                : null)
                .createdAt(timesheet.getCreatedAt())
                .updatedAt(timesheet.getUpdatedAt())
                .build();
    }
}
