package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.approval.ApprovalResponse;
import com.contingentworkforce.dto.common.PageResponse;
import com.contingentworkforce.entity.Approval;
import com.contingentworkforce.entity.User;
import com.contingentworkforce.enums.ApprovalStatus;
import com.contingentworkforce.enums.EntityType;
import com.contingentworkforce.repository.ApprovalRepository;
import com.contingentworkforce.service.ApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class ApprovalServiceImpl implements ApprovalService {

    private final ApprovalRepository approvalRepository;

    @Override
    @Transactional
    public void recordApproval(EntityType entityType, UUID entityId, User submittedBy, User approvedBy, ApprovalStatus status, String comments) {
        Approval approval = Approval.builder()
                .entityType(entityType)
                .entityId(entityId)
                .submittedBy(submittedBy)
                .approvedBy(approvedBy)
                .status(status)
                .comments(comments)
                .build();

        approvalRepository.save(approval);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalResponse> getApprovalsForEntity(EntityType entityType, UUID entityId) {
        return approvalRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId).stream()
                .map(this::mapToApprovalResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ApprovalResponse> getPendingApprovals(Pageable pageable) {
        Page<Approval> page = approvalRepository.findByStatus(ApprovalStatus.PENDING, pageable);
        return PageResponse.from(page.map(this::mapToApprovalResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ApprovalResponse> getAllApprovals(Pageable pageable) {
        Page<Approval> page = approvalRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResponse.from(page.map(this::mapToApprovalResponse));
    }

    public ApprovalResponse mapToApprovalResponse(Approval approval) {
        if (approval == null) return null;
        return ApprovalResponse.builder()
                .id(approval.getId())
                .entityType(approval.getEntityType())
                .entityId(approval.getEntityId())
                .submittedBy(AuthServiceImpl.mapToUserResponse(approval.getSubmittedBy()))
                .approvedBy(AuthServiceImpl.mapToUserResponse(approval.getApprovedBy()))
                .status(approval.getStatus())
                .comments(approval.getComments())
                .createdAt(approval.getCreatedAt())
                .updatedAt(approval.getUpdatedAt())
                .build();
    }
}
