package com.contingentworkforce;

import com.contingentworkforce.dto.report.VendorPerformanceDTO;
import com.contingentworkforce.entity.*;
import com.contingentworkforce.enums.InvoiceStatus;
import com.contingentworkforce.enums.MilestoneStatus;
import com.contingentworkforce.enums.TimesheetStatus;
import com.contingentworkforce.repository.*;
import com.contingentworkforce.service.impl.VendorPerformanceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class VendorPerformanceServiceTests {

    @Mock
    private VendorRepository vendorRepository;
    @Mock
    private ContractorRepository contractorRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private TimesheetRepository timesheetRepository;
    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private MilestoneRepository milestoneRepository;

    @InjectMocks
    private VendorPerformanceServiceImpl vendorPerformanceService;

    private Vendor vendor;
    private UUID vendorId;
    private Contractor contractor;
    private Project project;

    @BeforeEach
    void setUp() {
        vendorId = UUID.randomUUID();
        vendor = Vendor.builder().id(vendorId).vendorName("Acme Global").build();
        contractor = Contractor.builder().id(UUID.randomUUID()).vendor(vendor).build();
        project = Project.builder().id(UUID.randomUUID()).vendor(vendor).build();
    }

    @Test
    @DisplayName("Should return grade A+ (100 score) when all timesheets, invoices, and milestones are approved")
    void testPerfectVendorScore() {
        when(vendorRepository.findById(vendorId)).thenReturn(Optional.of(vendor));
        when(contractorRepository.findByVendorId(vendorId)).thenReturn(List.of(contractor));
        when(projectRepository.findByVendorId(vendorId)).thenReturn(List.of(project));

        Timesheet t1 = Timesheet.builder().status(TimesheetStatus.APPROVED).build();
        when(timesheetRepository.findAll(org.mockito.ArgumentMatchers.<Specification<Timesheet>>any())).thenReturn(List.of(t1));


        Invoice inv1 = Invoice.builder().status(InvoiceStatus.APPROVED).differenceAmount(BigDecimal.ZERO).build();
        when(invoiceRepository.findByVendorId(vendorId)).thenReturn(List.of(inv1));

        Milestone m1 = Milestone.builder().status(MilestoneStatus.COMPLETED).build();
        when(milestoneRepository.findByProjectId(project.getId())).thenReturn(List.of(m1));

        VendorPerformanceDTO performance = vendorPerformanceService.calculateVendorPerformance(vendorId);

        assertNotNull(performance);
        assertEquals(100, performance.getScore());
        assertEquals("A+", performance.getGrade());
        assertEquals(100.0, performance.getTimesheetAccuracyRate());
        assertEquals(100.0, performance.getInvoiceAccuracyRate());
        assertEquals(100.0, performance.getMilestoneCompletionRate());
    }
}
