package com.contingentworkforce;

import com.contingentworkforce.dto.invoice.InvoiceResponse;
import com.contingentworkforce.entity.Invoice;
import com.contingentworkforce.entity.Project;
import com.contingentworkforce.entity.User;
import com.contingentworkforce.entity.Vendor;
import com.contingentworkforce.enums.InvoiceStatus;
import com.contingentworkforce.enums.Role;
import com.contingentworkforce.repository.InvoiceItemRepository;
import com.contingentworkforce.repository.InvoiceRepository;
import com.contingentworkforce.repository.UserRepository;
import com.contingentworkforce.service.ApprovalService;
import com.contingentworkforce.service.BillingCalculationService;
import com.contingentworkforce.service.NotificationService;
import com.contingentworkforce.service.impl.InvoiceServiceImpl;
import com.contingentworkforce.service.impl.VendorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class InvoiceValidationTests {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceItemRepository invoiceItemRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BillingCalculationService billingCalculationService;
    @Mock
    private ApprovalService approvalService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private VendorServiceImpl vendorService;

    @InjectMocks
    private InvoiceServiceImpl invoiceService;

    private Vendor vendor;
    private Project project;
    private User manager;
    private UUID invoiceId;

    @BeforeEach
    void setUp() {
        invoiceId = UUID.randomUUID();
        vendor = Vendor.builder().id(UUID.randomUUID()).vendorName("Apex Tech").build();
        manager = User.builder().id(UUID.randomUUID()).name("Manager").email("mgr@test.com").role(Role.MANAGER).build();
        project = Project.builder().id(UUID.randomUUID()).projectName("Cloud Migration").manager(manager).vendor(vendor).build();

        // Mock SecurityContext with a valid CustomUserDetails if needed
        com.contingentworkforce.security.CustomUserDetails userDetails = new com.contingentworkforce.security.CustomUserDetails(manager);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("Should validate invoice with 0 difference when vendor submitted total matches backend calculation")
    void testMatchingInvoiceValidation() {
        Invoice invoice = Invoice.builder()
                .id(invoiceId)
                .invoiceNumber("INV-001")
                .vendor(vendor)
                .project(project)
                .billingPeriodStart(LocalDate.of(2026, 1, 1))
                .billingPeriodEnd(LocalDate.of(2026, 1, 31))
                .subtotal(BigDecimal.valueOf(80000.00))
                .tax(BigDecimal.valueOf(14400.00))
                .totalAmount(BigDecimal.valueOf(94400.00))
                .status(InvoiceStatus.DRAFT)
                .build();

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        // Backend calculated total subtotal = 80,000
        when(billingCalculationService.calculateTotalBilling(project.getId(), invoice.getBillingPeriodStart(), invoice.getBillingPeriodEnd()))
                .thenReturn(BigDecimal.valueOf(80000.00));
        when(billingCalculationService.generateInvoiceItemsForPeriod(any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(any())).thenReturn(Optional.of(manager));

        InvoiceResponse response = invoiceService.submitInvoice(invoiceId);

        assertEquals(InvoiceStatus.UNDER_REVIEW, response.getStatus());
        assertEquals(0, BigDecimal.valueOf(94400.00).compareTo(response.getCalculatedAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.getDifferenceAmount()));
    }

    @Test
    @DisplayName("Should detect discrepancy when vendor submits ₹95,000 but backend calculates ₹80,000 (diff = ₹15,000)")
    void testDiscrepancyInvoiceValidation() {
        Invoice invoice = Invoice.builder()
                .id(invoiceId)
                .invoiceNumber("INV-002")
                .vendor(vendor)
                .project(project)
                .billingPeriodStart(LocalDate.of(2026, 1, 1))
                .billingPeriodEnd(LocalDate.of(2026, 1, 31))
                .subtotal(BigDecimal.valueOf(95000.00))
                .tax(BigDecimal.ZERO)
                .totalAmount(BigDecimal.valueOf(95000.00))
                .status(InvoiceStatus.DRAFT)
                .build();

        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        // Backend calculated subtotal = 80,000
        when(billingCalculationService.calculateTotalBilling(project.getId(), invoice.getBillingPeriodStart(), invoice.getBillingPeriodEnd()))
                .thenReturn(BigDecimal.valueOf(80000.00));
        when(billingCalculationService.generateInvoiceItemsForPeriod(any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(any())).thenReturn(Optional.of(manager));

        InvoiceResponse response = invoiceService.submitInvoice(invoiceId);

        assertEquals(InvoiceStatus.UNDER_REVIEW, response.getStatus());
        assertEquals(0, BigDecimal.valueOf(80000.00).compareTo(response.getCalculatedAmount()));
        assertEquals(0, BigDecimal.valueOf(15000.00).compareTo(response.getDifferenceAmount()));
    }
}
