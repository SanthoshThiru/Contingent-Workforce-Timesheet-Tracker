package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.common.PageResponse;
import com.contingentworkforce.dto.invoice.*;
import com.contingentworkforce.entity.*;
import com.contingentworkforce.enums.*;
import com.contingentworkforce.exception.AccessDeniedException;
import com.contingentworkforce.exception.DuplicateResourceException;
import com.contingentworkforce.exception.InvalidStateTransitionException;
import com.contingentworkforce.exception.ResourceNotFoundException;
import com.contingentworkforce.repository.*;
import com.contingentworkforce.security.SecurityUtils;
import com.contingentworkforce.service.ApprovalService;
import com.contingentworkforce.service.BillingCalculationService;
import com.contingentworkforce.service.InvoiceService;
import com.contingentworkforce.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final VendorRepository vendorRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final BillingCalculationService billingCalculationService;
    private final ApprovalService approvalService;
    private final NotificationService notificationService;
    private final VendorServiceImpl vendorService;

    @Override
    @Transactional
    public InvoiceResponse createInvoice(InvoiceRequest request) {
        if (invoiceRepository.existsByInvoiceNumber(request.getInvoiceNumber().trim())) {
            throw new DuplicateResourceException("Invoice number already exists: " + request.getInvoiceNumber());
        }

        Vendor vendor = resolveVendor(request.getVendorId());
        Project project = projectRepository.findById(request.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + request.getProjectId()));

        BigDecimal subtotal = request.getSubtotal() != null ? request.getSubtotal() : BigDecimal.ZERO;
        BigDecimal tax = request.getTax() != null ? request.getTax() : BigDecimal.ZERO;
        BigDecimal totalAmount = request.getTotalAmount() != null ? request.getTotalAmount() : subtotal.add(tax);

        Invoice invoice = Invoice.builder()
                .invoiceNumber(request.getInvoiceNumber().trim())
                .vendor(vendor)
                .project(project)
                .billingPeriodStart(request.getBillingPeriodStart())
                .billingPeriodEnd(request.getBillingPeriodEnd())
                .subtotal(subtotal)
                .tax(tax)
                .totalAmount(totalAmount)
                .calculatedAmount(BigDecimal.ZERO)
                .differenceAmount(BigDecimal.ZERO)
                .status(InvoiceStatus.DRAFT)
                .build();

        Invoice saved = invoiceRepository.save(invoice);
        return mapToInvoiceResponse(saved);
    }

    @Override
    @Transactional
    public InvoiceResponse updateInvoice(UUID id, InvoiceRequest request) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found with id: " + id));

        if (invoice.getStatus() != InvoiceStatus.DRAFT && invoice.getStatus() != InvoiceStatus.REJECTED) {
            throw new InvalidStateTransitionException("Only DRAFT or REJECTED invoices can be updated");
        }

        if (!invoice.getInvoiceNumber().equalsIgnoreCase(request.getInvoiceNumber().trim())) {
            if (invoiceRepository.existsByInvoiceNumber(request.getInvoiceNumber().trim())) {
                throw new DuplicateResourceException("Invoice number already exists: " + request.getInvoiceNumber());
            }
            invoice.setInvoiceNumber(request.getInvoiceNumber().trim());
        }

        if (request.getProjectId() != null && !request.getProjectId().equals(invoice.getProject().getId())) {
            Project project = projectRepository.findById(request.getProjectId())
                    .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + request.getProjectId()));
            invoice.setProject(project);
        }

        invoice.setBillingPeriodStart(request.getBillingPeriodStart());
        invoice.setBillingPeriodEnd(request.getBillingPeriodEnd());
        invoice.setSubtotal(request.getSubtotal());
        invoice.setTax(request.getTax() != null ? request.getTax() : BigDecimal.ZERO);
        invoice.setTotalAmount(request.getTotalAmount());
        if (invoice.getStatus() == InvoiceStatus.REJECTED) {
            invoice.setStatus(InvoiceStatus.DRAFT);
            invoice.setRejectionReason(null);
        }

        Invoice updated = invoiceRepository.save(invoice);
        return mapToInvoiceResponse(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponse getInvoiceById(UUID id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found with id: " + id));
        return mapToInvoiceResponse(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InvoiceResponse> getInvoices(UUID vendorId, UUID projectId, InvoiceStatus status, LocalDate startDate, LocalDate endDate, Pageable pageable) {
        UUID effectiveVendorId = vendorId;
        if (SecurityUtils.isVendor()) {
            Vendor vendor = vendorRepository.findByEmail(SecurityUtils.getCurrentUserEmail()).orElse(null);
            if (vendor != null) {
                effectiveVendorId = vendor.getId();
            } else {
                return PageResponse.empty();
            }
        }

        Page<Invoice> page = invoiceRepository.findWithFilters(effectiveVendorId, projectId, status, startDate, endDate, pageable);
        return PageResponse.from(page.map(this::mapToInvoiceResponse));
    }

    @Override
    @Transactional
    public InvoiceResponse submitInvoice(UUID id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found with id: " + id));

        if (invoice.getStatus() != InvoiceStatus.DRAFT && invoice.getStatus() != InvoiceStatus.REJECTED) {
            throw new InvalidStateTransitionException("Only DRAFT or REJECTED invoices can be submitted. Current status: " + invoice.getStatus());
        }

        // 1. Calculate backend verified billing
        BigDecimal backendSubtotal = billingCalculationService.calculateTotalBilling(
                invoice.getProject().getId(),
                invoice.getBillingPeriodStart(),
                invoice.getBillingPeriodEnd()
        );

        BigDecimal tax = invoice.getTax() != null ? invoice.getTax() : BigDecimal.ZERO;
        BigDecimal backendCalculatedTotal = backendSubtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal difference = invoice.getTotalAmount().subtract(backendCalculatedTotal).abs().setScale(2, RoundingMode.HALF_UP);

        invoice.setCalculatedAmount(backendCalculatedTotal);
        invoice.setDifferenceAmount(difference);
        invoice.setStatus(InvoiceStatus.UNDER_REVIEW);
        invoice.setSubmittedAt(LocalDateTime.now());
        invoice.setRejectionReason(null);

        // 2. Generate and attach invoice line items
        invoiceItemRepository.deleteByInvoiceId(invoice.getId());
        List<InvoiceItem> items = billingCalculationService.generateInvoiceItemsForPeriod(
                invoice.getProject().getId(),
                invoice.getBillingPeriodStart(),
                invoice.getBillingPeriodEnd()
        );
        for (InvoiceItem item : items) {
            item.setInvoice(invoice);
        }
        invoiceItemRepository.saveAll(items);

        Invoice saved = invoiceRepository.save(invoice);

        // 3. Record approval history
        User currentUser = userRepository.findById(SecurityUtils.getCurrentUserId()).orElse(null);
        approvalService.recordApproval(
                EntityType.INVOICE,
                invoice.getId(),
                currentUser,
                null,
                ApprovalStatus.PENDING,
                String.format("Invoice submitted. Submitted: %s, Backend Calculated: %s, Difference: %s",
                        invoice.getTotalAmount(), backendCalculatedTotal, difference)
        );

        // 4. Notifications for Project Manager
        if (invoice.getProject().getManager() != null) {
            if (difference.compareTo(BigDecimal.ZERO) > 0) {
                notificationService.createNotification(
                        invoice.getProject().getManager(),
                        "Invoice Discrepancy Alert",
                        String.format("Invoice %s submitted by %s for project '%s' has a mismatch of %s (Claimed: %s, Calculated: %s).",
                                invoice.getInvoiceNumber(),
                                invoice.getVendor().getVendorName(),
                                invoice.getProject().getProjectName(),
                                difference,
                                invoice.getTotalAmount(),
                                backendCalculatedTotal),
                        NotificationType.INVOICE
                );
            } else {
                notificationService.createNotification(
                        invoice.getProject().getManager(),
                        "Invoice Submitted for Review",
                        String.format("Invoice %s for project '%s' was submitted by %s and verified successfully (%s).",
                                invoice.getInvoiceNumber(),
                                invoice.getProject().getProjectName(),
                                invoice.getVendor().getVendorName(),
                                invoice.getTotalAmount()),
                        NotificationType.INVOICE
                );
            }
        }

        return mapToInvoiceResponse(saved);
    }

    @Override
    @Transactional
    public InvoiceResponse approveInvoice(UUID id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found with id: " + id));

        if (invoice.getStatus() != InvoiceStatus.UNDER_REVIEW && invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvalidStateTransitionException("Only invoices UNDER_REVIEW can be approved. Current status: " + invoice.getStatus());
        }

        User approver = userRepository.findById(SecurityUtils.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Approver user not found"));

        invoice.setStatus(InvoiceStatus.APPROVED);
        invoice.setApprovedAt(LocalDateTime.now());
        invoice.setApprovedBy(approver);
        Invoice updated = invoiceRepository.save(invoice);

        // Record Approval History
        approvalService.recordApproval(
                EntityType.INVOICE,
                invoice.getId(),
                null,
                approver,
                ApprovalStatus.APPROVED,
                "Invoice approved by " + approver.getName()
        );

        // Notify Vendor
        if (invoice.getVendor().getEmail() != null) {
            userRepository.findByEmail(invoice.getVendor().getEmail()).ifPresent(vendorUser ->
                    notificationService.createNotification(
                            vendorUser,
                            "Invoice Approved",
                            String.format("Your invoice %s for project '%s' (%s) has been approved by %s.",
                                    invoice.getInvoiceNumber(),
                                    invoice.getProject().getProjectName(),
                                    invoice.getTotalAmount(),
                                    approver.getName()),
                            NotificationType.INVOICE
                    )
            );
        }

        return mapToInvoiceResponse(updated);
    }

    @Override
    @Transactional
    public InvoiceResponse rejectInvoice(UUID id, InvoiceRejectRequest rejectRequest) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found with id: " + id));

        if (invoice.getStatus() != InvoiceStatus.UNDER_REVIEW) {
            throw new InvalidStateTransitionException("Only invoices UNDER_REVIEW can be rejected. Current status: " + invoice.getStatus());
        }

        User approver = userRepository.findById(SecurityUtils.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Approver user not found"));

        String reason = rejectRequest.getReason() != null ? rejectRequest.getReason().trim() : "No reason provided";
        invoice.setStatus(InvoiceStatus.REJECTED);
        invoice.setRejectionReason(reason);
        Invoice updated = invoiceRepository.save(invoice);

        // Record Approval History
        approvalService.recordApproval(
                EntityType.INVOICE,
                invoice.getId(),
                null,
                approver,
                ApprovalStatus.REJECTED,
                "Invoice rejected: " + reason
        );

        // Notify Vendor
        if (invoice.getVendor().getEmail() != null) {
            userRepository.findByEmail(invoice.getVendor().getEmail()).ifPresent(vendorUser ->
                    notificationService.createNotification(
                            vendorUser,
                            "Invoice Rejected",
                            String.format("Your invoice %s for project '%s' has been rejected. Reason: %s",
                                    invoice.getInvoiceNumber(),
                                    invoice.getProject().getProjectName(),
                                    reason),
                            NotificationType.INVOICE
                    )
            );
        }

        return mapToInvoiceResponse(updated);
    }

    @Override
    @Transactional
    public InvoiceResponse markPaid(UUID id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found with id: " + id));

        if (invoice.getStatus() != InvoiceStatus.APPROVED) {
            throw new InvalidStateTransitionException("Only APPROVED invoices can be marked as PAID. Current status: " + invoice.getStatus());
        }

        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(LocalDateTime.now());
        Invoice updated = invoiceRepository.save(invoice);

        // Record Approval History
        User currentUser = userRepository.findById(SecurityUtils.getCurrentUserId()).orElse(null);
        approvalService.recordApproval(
                EntityType.INVOICE,
                invoice.getId(),
                null,
                currentUser,
                ApprovalStatus.APPROVED,
                "Invoice payment confirmed and settled."
        );

        // Notify Vendor
        if (invoice.getVendor().getEmail() != null) {
            userRepository.findByEmail(invoice.getVendor().getEmail()).ifPresent(vendorUser ->
                    notificationService.createNotification(
                            vendorUser,
                            "Invoice Payment Received",
                            String.format("Payment for invoice %s (%s) has been processed and marked PAID.",
                                    invoice.getInvoiceNumber(),
                                    invoice.getTotalAmount()),
                            NotificationType.INVOICE
                    )
            );
        }

        return mapToInvoiceResponse(updated);
    }

    @Override
    @Transactional
    public void deleteInvoice(UUID id) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found with id: " + id));

        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new InvalidStateTransitionException("Only DRAFT invoices can be deleted");
        }
        invoiceItemRepository.deleteByInvoiceId(invoice.getId());
        invoiceRepository.delete(invoice);
    }

    private Vendor resolveVendor(UUID requestVendorId) {
        if (requestVendorId != null) {
            return vendorRepository.findById(requestVendorId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + requestVendorId));
        }
        if (SecurityUtils.isVendor()) {
            return vendorRepository.findByEmail(SecurityUtils.getCurrentUserEmail())
                    .orElseThrow(() -> new ResourceNotFoundException("Vendor profile not found for logged in user"));
        }
        throw new AccessDeniedException("Vendor ID is required");
    }

    public InvoiceResponse mapToInvoiceResponse(Invoice invoice) {
        if (invoice == null) return null;
        List<InvoiceItemResponse> items = invoiceItemRepository.findByInvoiceId(invoice.getId()).stream()
                .map(this::mapToInvoiceItemResponse)
                .collect(Collectors.toList());

        return InvoiceResponse.builder()
                .id(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .vendor(vendorService.mapToVendorResponse(invoice.getVendor()))
                .projectId(invoice.getProject().getId())
                .projectName(invoice.getProject().getProjectName())
                .billingPeriodStart(invoice.getBillingPeriodStart())
                .billingPeriodEnd(invoice.getBillingPeriodEnd())
                .subtotal(invoice.getSubtotal())
                .tax(invoice.getTax())
                .totalAmount(invoice.getTotalAmount())
                .calculatedAmount(invoice.getCalculatedAmount())
                .differenceAmount(invoice.getDifferenceAmount())
                .status(invoice.getStatus())
                .rejectionReason(invoice.getRejectionReason())
                .submittedAt(invoice.getSubmittedAt())
                .approvedAt(invoice.getApprovedAt())
                .approvedBy(invoice.getApprovedBy() != null ? AuthServiceImpl.mapToUserResponse(invoice.getApprovedBy()) : null)
                .paidAt(invoice.getPaidAt())
                .items(items)
                .createdAt(invoice.getCreatedAt())
                .updatedAt(invoice.getUpdatedAt())
                .build();
    }

    public InvoiceItemResponse mapToInvoiceItemResponse(InvoiceItem item) {
        if (item == null) return null;
        return InvoiceItemResponse.builder()
                .id(item.getId())
                .invoiceId(item.getInvoice().getId())
                .itemType(item.getItemType())
                .referenceId(item.getReferenceId())
                .description(item.getDescription())
                .quantity(item.getQuantity())
                .rate(item.getRate())
                .amount(item.getAmount())
                .build();
    }
}
