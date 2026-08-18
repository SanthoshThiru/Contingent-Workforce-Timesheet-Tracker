package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.report.*;
import com.contingentworkforce.entity.*;
import com.contingentworkforce.enums.InvoiceStatus;
import com.contingentworkforce.enums.ProjectStatus;
import com.contingentworkforce.enums.TimesheetStatus;
import com.contingentworkforce.repository.*;
import com.contingentworkforce.service.ReportService;
import com.contingentworkforce.service.VendorPerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class ReportServiceImpl implements ReportService {

    private final VendorRepository vendorRepository;
    private final ContractorRepository contractorRepository;
    private final ProjectRepository projectRepository;
    private final TimesheetRepository timesheetRepository;
    private final InvoiceRepository invoiceRepository;
    private final ApprovalRepository approvalRepository;
    private final VendorPerformanceService vendorPerformanceService;
    private final InvoiceServiceImpl invoiceService;

    @Override
    @Transactional(readOnly = true)
    public DashboardResponse getDashboardMetrics() {
        long totalVendors = vendorRepository.count();
        long totalContractors = contractorRepository.count();
        long activeProjects = projectRepository.countByStatus(ProjectStatus.ACTIVE);
        long pendingTimesheets = timesheetRepository.countByStatus(TimesheetStatus.SUBMITTED);
        long pendingInvoices = invoiceRepository.countByStatus(InvoiceStatus.SUBMITTED) +
                               invoiceRepository.countByStatus(InvoiceStatus.UNDER_REVIEW);

        List<Invoice> allInvoices = invoiceRepository.findAll();
        BigDecimal totalBilling = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;

        for (Invoice inv : allInvoices) {
            if (inv.getStatus() != InvoiceStatus.DRAFT && inv.getStatus() != InvoiceStatus.REJECTED) {
                totalBilling = totalBilling.add(inv.getTotalAmount());
            }
            if (inv.getStatus() == InvoiceStatus.PAID) {
                totalPaid = totalPaid.add(inv.getTotalAmount());
            }
        }

        // 1. Monthly Billing Breakdown (Last 6 Months)
        Map<String, MonthlyBillingDTO> monthMap = new TreeMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM");
        LocalDate now = LocalDate.now();
        for (int i = 5; i >= 0; i--) {
            LocalDate m = now.minusMonths(i);
            String key = m.format(formatter);
            monthMap.put(key, new MonthlyBillingDTO(key, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        for (Invoice inv : allInvoices) {
            if (inv.getStatus() != InvoiceStatus.DRAFT) {
                String monthKey = inv.getBillingPeriodStart().format(formatter);
                if (monthMap.containsKey(monthKey)) {
                    MonthlyBillingDTO dto = monthMap.get(monthKey);
                    dto.setAmount(dto.getAmount().add(inv.getTotalAmount()));
                }
            }
        }

        List<Timesheet> allTimesheets = timesheetRepository.findAll();
        for (Timesheet t : allTimesheets) {
            if (t.getStatus() == TimesheetStatus.APPROVED) {
                String monthKey = t.getWorkDate().format(formatter);
                if (monthMap.containsKey(monthKey)) {
                    MonthlyBillingDTO dto = monthMap.get(monthKey);
                    dto.setHours(dto.getHours().add(t.getTotalHours()));
                }
            }
        }

        List<MonthlyBillingDTO> monthlyBilling = new ArrayList<>(monthMap.values());

        // 2. Contractor Hours Breakdown
        List<ContractorHoursDTO> contractorHours = new ArrayList<>();
        List<Contractor> contractors = contractorRepository.findAll();
        for (Contractor c : contractors) {
            List<Timesheet> cTimesheets = allTimesheets.stream()
                    .filter(t -> t.getContractor().getId().equals(c.getId()))
                    .toList();

            BigDecimal approvedHours = BigDecimal.ZERO;
            BigDecimal pendingHours = BigDecimal.ZERO;
            String lastProjectName = "None";

            for (Timesheet t : cTimesheets) {
                lastProjectName = t.getProject().getProjectName();
                if (t.getStatus() == TimesheetStatus.APPROVED) {
                    approvedHours = approvedHours.add(t.getTotalHours());
                } else if (t.getStatus() == TimesheetStatus.SUBMITTED) {
                    pendingHours = pendingHours.add(t.getTotalHours());
                }
            }

            BigDecimal totalBilled = approvedHours.multiply(c.getHourlyRate()).setScale(2, RoundingMode.HALF_UP);

            contractorHours.add(ContractorHoursDTO.builder()
                    .contractorId(c.getId())
                    .contractorName(c.getUser().getName())
                    .vendorName(c.getVendor().getVendorName())
                    .projectName(lastProjectName)
                    .approvedHours(approvedHours)
                    .pendingHours(pendingHours)
                    .totalBilledAmount(totalBilled)
                    .build());
        }

        // 3. Invoice Status Counts
        Map<InvoiceStatus, InvoiceStatusDTO> statusMap = new HashMap<>();
        for (InvoiceStatus status : InvoiceStatus.values()) {
            statusMap.put(status, new InvoiceStatusDTO(status, 0L, BigDecimal.ZERO));
        }

        for (Invoice inv : allInvoices) {
            InvoiceStatusDTO dto = statusMap.get(inv.getStatus());
            if (dto != null) {
                dto.setCount(dto.getCount() + 1);
                dto.setTotalAmount(dto.getTotalAmount().add(inv.getTotalAmount()));
            }
        }
        List<InvoiceStatusDTO> invoiceStatuses = new ArrayList<>(statusMap.values());

        // 4. Vendor Performance
        List<VendorPerformanceDTO> vendorPerformances = vendorPerformanceService.getAllVendorPerformances();

        // 5. Recent Activities
        List<Approval> approvals = approvalRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10)).getContent();
        List<RecentActivityDTO> recentActivities = approvals.stream()
                .map(a -> RecentActivityDTO.builder()
                        .id(a.getId())
                        .entityType(a.getEntityType().name())
                        .action(a.getStatus().name())
                        .title(a.getEntityType().name() + " " + a.getStatus().name())
                        .description(a.getComments())
                        .actorName(a.getApprovedBy() != null ? a.getApprovedBy().getName() : (a.getSubmittedBy() != null ? a.getSubmittedBy().getName() : "System"))
                        .actorRole(a.getApprovedBy() != null ? a.getApprovedBy().getRole().name() : "SYSTEM")
                        .status(a.getStatus().name())
                        .timestamp(a.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return DashboardResponse.builder()
                .totalVendors(totalVendors)
                .totalContractors(totalContractors)
                .activeProjects(activeProjects)
                .pendingTimesheets(pendingTimesheets)
                .pendingInvoices(pendingInvoices)
                .totalBilling(totalBilling)
                .totalPaid(totalPaid)
                .monthlyBilling(monthlyBilling)
                .contractorHours(contractorHours)
                .invoiceStatus(invoiceStatuses)
                .vendorPerformance(vendorPerformances)
                .recentActivities(recentActivities)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public BillingReportResponse getBillingReport(UUID vendorId, UUID projectId, LocalDate startDate, LocalDate endDate) {
        List<Invoice> invoices = invoiceRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (vendorId != null) predicates.add(cb.equal(root.get("vendor").get("id"), vendorId));
            if (projectId != null) predicates.add(cb.equal(root.get("project").get("id"), projectId));
            if (startDate != null) predicates.add(cb.greaterThanOrEqualTo(root.get("billingPeriodStart"), startDate));
            if (endDate != null) predicates.add(cb.lessThanOrEqualTo(root.get("billingPeriodEnd"), endDate));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        BigDecimal totalBilled = BigDecimal.ZERO;
        BigDecimal totalApproved = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalDiscrepancies = BigDecimal.ZERO;

        for (Invoice inv : invoices) {
            totalBilled = totalBilled.add(inv.getTotalAmount());
            if (inv.getStatus() == InvoiceStatus.APPROVED || inv.getStatus() == InvoiceStatus.PAID) {
                totalApproved = totalApproved.add(inv.getTotalAmount());
            }
            if (inv.getStatus() == InvoiceStatus.PAID) {
                totalPaid = totalPaid.add(inv.getTotalAmount());
            }
            if (inv.getDifferenceAmount() != null) {
                totalDiscrepancies = totalDiscrepancies.add(inv.getDifferenceAmount());
            }
        }

        List<Timesheet> timesheets = timesheetRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (projectId != null) predicates.add(cb.equal(root.get("project").get("id"), projectId));
            if (startDate != null) predicates.add(cb.greaterThanOrEqualTo(root.get("workDate"), startDate));
            if (endDate != null) predicates.add(cb.lessThanOrEqualTo(root.get("workDate"), endDate));
            predicates.add(cb.equal(root.get("status"), TimesheetStatus.APPROVED));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        BigDecimal totalHours = BigDecimal.ZERO;
        for (Timesheet t : timesheets) {
            totalHours = totalHours.add(t.getTotalHours());
        }

        return BillingReportResponse.builder()
                .startDate(startDate)
                .endDate(endDate)
                .totalBilled(totalBilled.setScale(2, RoundingMode.HALF_UP))
                .totalApproved(totalApproved.setScale(2, RoundingMode.HALF_UP))
                .totalPaid(totalPaid.setScale(2, RoundingMode.HALF_UP))
                .totalDiscrepancies(totalDiscrepancies.setScale(2, RoundingMode.HALF_UP))
                .totalTimesheetHours(totalHours.setScale(2, RoundingMode.HALF_UP))
                .invoices(invoices.stream().map(invoiceService::mapToInvoiceResponse).collect(Collectors.toList()))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<VendorPerformanceDTO> getVendorPerformanceReport() {
        return vendorPerformanceService.getAllVendorPerformances();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractorHoursDTO> getContractorHoursReport(UUID projectId, LocalDate startDate, LocalDate endDate) {
        List<Timesheet> timesheets = timesheetRepository.findAll((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (projectId != null) predicates.add(cb.equal(root.get("project").get("id"), projectId));
            if (startDate != null) predicates.add(cb.greaterThanOrEqualTo(root.get("workDate"), startDate));
            if (endDate != null) predicates.add(cb.lessThanOrEqualTo(root.get("workDate"), endDate));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        Map<UUID, ContractorHoursDTO> summaryMap = new HashMap<>();
        for (Timesheet t : timesheets) {
            UUID cId = t.getContractor().getId();
            ContractorHoursDTO dto = summaryMap.computeIfAbsent(cId, k -> ContractorHoursDTO.builder()
                    .contractorId(cId)
                    .contractorName(t.getContractor().getUser().getName())
                    .vendorName(t.getContractor().getVendor().getVendorName())
                    .projectName(t.getProject().getProjectName())
                    .approvedHours(BigDecimal.ZERO)
                    .pendingHours(BigDecimal.ZERO)
                    .totalBilledAmount(BigDecimal.ZERO)
                    .build());

            if (t.getStatus() == TimesheetStatus.APPROVED) {
                dto.setApprovedHours(dto.getApprovedHours().add(t.getTotalHours()));
            } else if (t.getStatus() == TimesheetStatus.SUBMITTED) {
                dto.setPendingHours(dto.getPendingHours().add(t.getTotalHours()));
            }
        }

        for (ContractorHoursDTO dto : summaryMap.values()) {
            Contractor c = contractorRepository.findById(dto.getContractorId()).orElse(null);
            if (c != null) {
                dto.setTotalBilledAmount(dto.getApprovedHours().multiply(c.getHourlyRate()).setScale(2, RoundingMode.HALF_UP));
            }
        }

        return new ArrayList<>(summaryMap.values());
    }
}
