package com.contingentworkforce.service.impl;

import com.contingentworkforce.dto.report.VendorPerformanceDTO;
import com.contingentworkforce.entity.*;
import com.contingentworkforce.enums.InvoiceStatus;
import com.contingentworkforce.enums.MilestoneStatus;
import com.contingentworkforce.enums.TimesheetStatus;
import com.contingentworkforce.exception.ResourceNotFoundException;
import com.contingentworkforce.repository.*;
import com.contingentworkforce.service.VendorPerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class VendorPerformanceServiceImpl implements VendorPerformanceService {

    private final VendorRepository vendorRepository;
    private final ContractorRepository contractorRepository;
    private final ProjectRepository projectRepository;
    private final TimesheetRepository timesheetRepository;
    private final InvoiceRepository invoiceRepository;
    private final MilestoneRepository milestoneRepository;

    @Override
    @Transactional(readOnly = true)
    public VendorPerformanceDTO calculateVendorPerformance(UUID vendorId) {
        Vendor vendor = vendorRepository.findById(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with id: " + vendorId));

        List<Contractor> contractors = contractorRepository.findByVendorId(vendorId);
        List<Project> projects = projectRepository.findByVendorId(vendorId);

        // 1. Timesheet Accuracy (30 Points)
        double timesheetPts = 30.0;
        double timesheetAccuracyRate = 100.0;
        int totalTimesheets = 0;
        int approvedTimesheets = 0;
        int rejectedTimesheets = 0;

        for (Contractor c : contractors) {
            List<Timesheet> tsList = timesheetRepository.findAll((root, query, cb) -> cb.equal(root.get("contractor").get("id"), c.getId()));
            for (Timesheet t : tsList) {
                if (t.getStatus() != TimesheetStatus.DRAFT) {
                    totalTimesheets++;
                    if (t.getStatus() == TimesheetStatus.APPROVED) {
                        approvedTimesheets++;
                    } else if (t.getStatus() == TimesheetStatus.REJECTED) {
                        rejectedTimesheets++;
                    }
                }
            }
        }

        if (totalTimesheets > 0) {
            timesheetAccuracyRate = ((double) approvedTimesheets / totalTimesheets) * 100.0;
            timesheetPts = (timesheetAccuracyRate / 100.0) * 30.0;
        }

        // 2. Invoice Accuracy (30 Points)
        double invoicePts = 30.0;
        double invoiceAccuracyRate = 100.0;
        List<Invoice> invoices = invoiceRepository.findByVendorId(vendorId);
        int totalInvoices = 0;
        int perfectInvoices = 0;
        int rejectedInvoices = 0;

        for (Invoice inv : invoices) {
            if (inv.getStatus() != InvoiceStatus.DRAFT) {
                totalInvoices++;
                if (inv.getDifferenceAmount().compareTo(BigDecimal.ZERO) == 0 && inv.getStatus() != InvoiceStatus.REJECTED) {
                    perfectInvoices++;
                }
                if (inv.getStatus() == InvoiceStatus.REJECTED) {
                    rejectedInvoices++;
                }
            }
        }

        if (totalInvoices > 0) {
            invoiceAccuracyRate = ((double) perfectInvoices / totalInvoices) * 100.0;
            invoicePts = (invoiceAccuracyRate / 100.0) * 30.0;
        }

        // 3. Milestone Completion (20 Points)
        double milestonePts = 20.0;
        double milestoneCompletionRate = 100.0;
        int totalMilestones = 0;
        int completedMilestones = 0;

        for (Project p : projects) {
            List<Milestone> milestones = milestoneRepository.findByProjectId(p.getId());
            for (Milestone m : milestones) {
                totalMilestones++;
                if (m.getStatus() == MilestoneStatus.COMPLETED) {
                    completedMilestones++;
                }
            }
        }

        if (totalMilestones > 0) {
            milestoneCompletionRate = ((double) completedMilestones / totalMilestones) * 100.0;
            milestonePts = (milestoneCompletionRate / 100.0) * 20.0;
        }

        // 4. SLA & Operational Reliability (20 Points)
        double operationalPts = 20.0;
        int penalty = (rejectedTimesheets * 3) + (rejectedInvoices * 5);
        operationalPts = Math.max(0.0, operationalPts - penalty);

        int totalScore = (int) Math.round(timesheetPts + invoicePts + milestonePts + operationalPts);
        totalScore = Math.max(0, Math.min(100, totalScore));

        String grade;
        if (totalScore >= 90) grade = "A+";
        else if (totalScore >= 80) grade = "A";
        else if (totalScore >= 70) grade = "B";
        else if (totalScore >= 60) grade = "C";
        else grade = "D";

        return VendorPerformanceDTO.builder()
                .vendorId(vendor.getId())
                .vendorName(vendor.getVendorName())
                .score(totalScore)
                .grade(grade)
                .timesheetAccuracyRate(Math.round(timesheetAccuracyRate * 10.0) / 10.0)
                .invoiceAccuracyRate(Math.round(invoiceAccuracyRate * 10.0) / 10.0)
                .milestoneCompletionRate(Math.round(milestoneCompletionRate * 10.0) / 10.0)
                .totalContractors(contractors.size())
                .activeProjects(projects.size())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<VendorPerformanceDTO> getAllVendorPerformances() {
        return vendorRepository.findAll().stream()
                .map(v -> calculateVendorPerformance(v.getId()))
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
    }
}
