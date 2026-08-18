package com.contingentworkforce.config;

import com.contingentworkforce.entity.*;
import com.contingentworkforce.enums.*;
import com.contingentworkforce.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;


@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("null")
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final VendorRepository vendorRepository;
    private final ContractorRepository contractorRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final TimesheetRepository timesheetRepository;
    private final MilestoneRepository milestoneRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final ApprovalRepository approvalRepository;
    private final NotificationRepository notificationRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Database already contains users. Skipping initial seed.");
            return;
        }

        log.info("Database is empty. Seeding demo users, vendors, projects, timesheets, and invoices...");

        String defaultHashedPassword = passwordEncoder.encode("Password123!");

        // 1. Create Users
        User admin = User.builder()
                .name("Alexander Admin")
                .email("admin@example.com")
                .passwordHash(defaultHashedPassword)
                .role(Role.ADMIN)
                .phone("+1-555-0101")
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(admin);

        User manager = User.builder()
                .name("Michael Manager")
                .email("manager@example.com")
                .passwordHash(defaultHashedPassword)
                .role(Role.MANAGER)
                .phone("+1-555-0102")
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(manager);

        User vendorUser = User.builder()
                .name("Victor Vendor")
                .email("vendor@example.com")
                .passwordHash(defaultHashedPassword)
                .role(Role.VENDOR)
                .phone("+1-555-0103")
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(vendorUser);

        User contractorUser1 = User.builder()
                .name("John Contractor")
                .email("contractor@example.com")
                .passwordHash(defaultHashedPassword)
                .role(Role.CONTRACTOR)
                .phone("+1-555-0104")
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(contractorUser1);

        User contractorUser2 = User.builder()
                .name("Sarah DevOps")
                .email("contractor2@example.com")
                .passwordHash(defaultHashedPassword)
                .role(Role.CONTRACTOR)
                .phone("+1-555-0105")
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(contractorUser2);

        // 2. Create Vendors
        Vendor vendor1 = Vendor.builder()
                .vendorName("Apex Global Technologies")
                .contactPerson("Victor Vendor")
                .email("vendor@example.com")
                .phone("+1-555-0103")
                .address("100 Silicon Way, San Jose, CA")
                .contractStartDate(LocalDate.of(2025, 1, 1))
                .contractEndDate(LocalDate.of(2027, 12, 31))
                .status(VendorStatus.ACTIVE)
                .manager(manager)
                .build();
        vendorRepository.save(vendor1);

        Vendor vendor2 = Vendor.builder()
                .vendorName("Nexus Talent Solutions")
                .contactPerson("Rachel Green")
                .email("nexus@example.com")
                .phone("+1-555-0199")
                .address("450 Innovation Blvd, Austin, TX")
                .contractStartDate(LocalDate.of(2025, 3, 1))
                .contractEndDate(LocalDate.of(2027, 3, 1))
                .status(VendorStatus.ACTIVE)
                .manager(manager)
                .build();
        vendorRepository.save(vendor2);

        // 3. Create Contractors
        Contractor contractor1 = Contractor.builder()
                .user(contractorUser1)
                .vendor(vendor1)
                .jobRole("Senior Full Stack Engineer")
                .hourlyRate(BigDecimal.valueOf(650.00))
                .startDate(LocalDate.of(2025, 1, 15))
                .endDate(LocalDate.of(2026, 12, 31))
                .status(ContractorStatus.ACTIVE)
                .build();
        contractorRepository.save(contractor1);

        Contractor contractor2 = Contractor.builder()
                .user(contractorUser2)
                .vendor(vendor2)
                .jobRole("Cloud DevOps Architect")
                .hourlyRate(BigDecimal.valueOf(850.00))
                .startDate(LocalDate.of(2025, 2, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .status(ContractorStatus.ACTIVE)
                .build();
        contractorRepository.save(contractor2);

        // 4. Create Projects
        Project project1 = Project.builder()
                .projectName("Enterprise Cloud Migration")
                .clientName("FinTech Global Corp")
                .description("Migration of legacy monolithic core to AWS microservices architecture.")
                .vendor(vendor1)
                .manager(manager)
                .startDate(LocalDate.of(2025, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .budget(BigDecimal.valueOf(650000.00))
                .status(ProjectStatus.ACTIVE)
                .build();
        projectRepository.save(project1);

        Project project2 = Project.builder()
                .projectName("AI Timesheet & Fraud Analytics")
                .clientName("Acro Logistics")
                .description("Real-time anomaly detection and timesheet auditing platform.")
                .vendor(vendor2)
                .manager(manager)
                .startDate(LocalDate.of(2025, 4, 1))
                .endDate(LocalDate.of(2026, 10, 31))
                .budget(BigDecimal.valueOf(400000.00))
                .status(ProjectStatus.ACTIVE)
                .build();
        projectRepository.save(project2);

        // 5. Assign Members
        ProjectMember member1 = ProjectMember.builder()
                .project(project1)
                .contractor(contractor1)
                .assignedDate(LocalDate.of(2025, 1, 15))
                .endDate(LocalDate.of(2026, 12, 31))
                .status(MemberStatus.ACTIVE)
                .build();
        projectMemberRepository.save(member1);

        ProjectMember member2 = ProjectMember.builder()
                .project(project2)
                .contractor(contractor2)
                .assignedDate(LocalDate.of(2025, 4, 1))
                .endDate(LocalDate.of(2026, 10, 31))
                .status(MemberStatus.ACTIVE)
                .build();
        projectMemberRepository.save(member2);


        // 6. Create Milestones
        Milestone m1 = Milestone.builder()
                .project(project1)
                .milestoneName("Cloud Architecture Blueprint")
                .description("Comprehensive architecture sign-off for AWS landing zone")
                .startDate(LocalDate.now().minusMonths(2))
                .dueDate(LocalDate.now().minusMonths(1))
                .assignedDays(15)
                .billingAmount(BigDecimal.valueOf(45000.00))
                .completionPercentage(100)
                .status(MilestoneStatus.COMPLETED)
                .approvedBy(manager)
                .approvedAt(LocalDateTime.now().minusMonths(1))
                .build();
        milestoneRepository.save(m1);

        Milestone m2 = Milestone.builder()
                .project(project1)
                .milestoneName("Core Data Services Migration")
                .description("Database migration of 10M records with zero downtime")
                .startDate(LocalDate.now().minusMonths(1))
                .dueDate(LocalDate.now().plusMonths(1))
                .assignedDays(20)
                .billingAmount(BigDecimal.valueOf(75000.00))
                .completionPercentage(100)
                .status(MilestoneStatus.COMPLETED)
                .approvedBy(manager)
                .approvedAt(LocalDateTime.now().minusDays(5))
                .build();
        milestoneRepository.save(m2);

        Milestone m3 = Milestone.builder()
                .project(project1)
                .milestoneName("Microservices API Gateway")
                .description("Unified Spring Cloud gateway with rate limiting")
                .startDate(LocalDate.now().minusDays(10))
                .dueDate(LocalDate.now().plusDays(10))
                .assignedDays(10)
                .billingAmount(BigDecimal.valueOf(50000.00))
                .completionPercentage(0)
                .status(MilestoneStatus.IN_PROGRESS)
                .build();
        milestoneRepository.save(m3);

        // 7. Create Timesheets
        Timesheet ts1 = Timesheet.builder()
                .contractor(contractor1)
                .project(project1)
                .milestone(m3)
                .workDate(LocalDate.now().minusDays(2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .breakHours(BigDecimal.valueOf(1.00))
                .totalHours(BigDecimal.valueOf(8.00))
                .description("Implemented JWT authentication filters and security context")
                .status(TimesheetStatus.APPROVED)
                .submittedAt(LocalDateTime.now().minusDays(2))
                .approvedAt(LocalDateTime.now().minusDays(2))
                .approvedBy(manager)
                .build();
        timesheetRepository.save(ts1);

        Timesheet ts2 = Timesheet.builder()
                .contractor(contractor1)
                .project(project1)
                .milestone(m3)
                .workDate(LocalDate.now().minusDays(1))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 30))
                .breakHours(BigDecimal.valueOf(0.50))
                .totalHours(BigDecimal.valueOf(8.00))
                .description("Developed Timesheet and Milestone JPA entities and repositories")
                .status(TimesheetStatus.APPROVED)
                .submittedAt(LocalDateTime.now().minusDays(1))
                .approvedAt(LocalDateTime.now().minusDays(1))
                .approvedBy(manager)
                .build();
        timesheetRepository.save(ts2);

        Timesheet ts3 = Timesheet.builder()
                .contractor(contractor1)
                .project(project1)
                .milestone(m3)
                .workDate(LocalDate.now())
                .startTime(LocalTime.of(9, 30))
                .endTime(LocalTime.of(18, 30))
                .breakHours(BigDecimal.valueOf(1.00))
                .totalHours(BigDecimal.valueOf(8.00))
                .description("Created automated invoice validation service and tests")
                .status(TimesheetStatus.SUBMITTED)
                .submittedAt(LocalDateTime.now())
                .build();
        timesheetRepository.save(ts3);

        // 8. Create Invoices
        Invoice inv1 = Invoice.builder()
                .invoiceNumber("INV-2026-001")
                .vendor(vendor1)
                .project(project1)
                .billingPeriodStart(LocalDate.now().minusMonths(1).withDayOfMonth(1))
                .billingPeriodEnd(LocalDate.now().minusMonths(1).withDayOfMonth(28))
                .subtotal(BigDecimal.valueOf(145000.00))
                .tax(BigDecimal.valueOf(26100.00))
                .totalAmount(BigDecimal.valueOf(171100.00))
                .calculatedAmount(BigDecimal.valueOf(171100.00))
                .differenceAmount(BigDecimal.ZERO)
                .status(InvoiceStatus.PAID)
                .submittedAt(LocalDateTime.now().minusDays(20))
                .approvedAt(LocalDateTime.now().minusDays(18))
                .approvedBy(manager)
                .paidAt(LocalDateTime.now().minusDays(15))
                .build();
        invoiceRepository.save(inv1);

        Invoice inv2 = Invoice.builder()
                .invoiceNumber("INV-2026-002")
                .vendor(vendor1)
                .project(project1)
                .billingPeriodStart(LocalDate.now().withDayOfMonth(1))
                .billingPeriodEnd(LocalDate.now())
                .subtotal(BigDecimal.valueOf(95000.00))
                .tax(BigDecimal.valueOf(17100.00))
                .totalAmount(BigDecimal.valueOf(112100.00))
                .calculatedAmount(BigDecimal.valueOf(97450.00))
                .differenceAmount(BigDecimal.valueOf(14650.00))
                .status(InvoiceStatus.UNDER_REVIEW)
                .submittedAt(LocalDateTime.now().minusDays(1))
                .build();
        invoiceRepository.save(inv2);

        // 9. Invoice Items
        InvoiceItem item1 = InvoiceItem.builder()
                .invoice(inv1)
                .itemType(InvoiceItemType.MILESTONE)
                .referenceId(m1.getId())
                .description("Cloud Architecture Blueprint")
                .quantity(BigDecimal.ONE)
                .rate(BigDecimal.valueOf(45000.00))
                .amount(BigDecimal.valueOf(45000.00))
                .build();
        invoiceItemRepository.save(item1);

        InvoiceItem item2 = InvoiceItem.builder()
                .invoice(inv1)
                .itemType(InvoiceItemType.TIMESHEET)
                .referenceId(ts1.getId())
                .description("Senior Full Stack Engineer (Jan approved hours)")
                .quantity(BigDecimal.valueOf(150.00))
                .rate(BigDecimal.valueOf(650.00))
                .amount(BigDecimal.valueOf(97500.00))
                .build();
        invoiceItemRepository.save(item2);

        // 10. Approvals
        Approval app1 = Approval.builder()
                .entityType(EntityType.TIMESHEET)
                .entityId(ts1.getId())
                .submittedBy(contractorUser1)
                .approvedBy(manager)
                .status(ApprovalStatus.APPROVED)
                .comments("Verified sprint delivery against Jira tickets.")
                .build();
        approvalRepository.save(app1);

        Approval app2 = Approval.builder()
                .entityType(EntityType.INVOICE)
                .entityId(inv1.getId())
                .submittedBy(vendorUser)
                .approvedBy(manager)
                .status(ApprovalStatus.APPROVED)
                .comments("Amounts fully verified against approved timesheets & milestones.")
                .build();
        approvalRepository.save(app2);

        // 11. Notifications
        Notification notif1 = Notification.builder()
                .user(manager)
                .title("Invoice Discrepancy Flagged")
                .message("Invoice INV-2026-002 from Apex Global Technologies has an automated variance of $14,650.00 requiring manual review.")
                .type(NotificationType.INVOICE)
                .isRead(false)
                .build();
        notificationRepository.save(notif1);

        Notification notif2 = Notification.builder()
                .user(manager)
                .title("New Timesheets Pending Approval")
                .message("3 timesheets submitted by John Contractor and Sarah DevOps are waiting for your review.")
                .type(NotificationType.TIMESHEET)
                .isRead(false)
                .build();
        notificationRepository.save(notif2);

        Notification notif3 = Notification.builder()
                .user(contractorUser1)
                .title("Timesheet Approved")
                .message("Your timesheet for Enterprise Cloud Migration (Sprint 12) was approved by Michael Manager.")
                .type(NotificationType.TIMESHEET)
                .isRead(false)
                .build();
        notificationRepository.save(notif3);

        Notification notif4 = Notification.builder()
                .user(contractorUser1)
                .title("Milestone Due Soon")
                .message("Project milestone 'Database Migration & Validation' is scheduled for completion next week.")
                .type(NotificationType.MILESTONE)
                .isRead(true)
                .build();
        notificationRepository.save(notif4);

        Notification notif5 = Notification.builder()
                .user(vendorUser)
                .title("Invoice Payment Processed")
                .message("Invoice INV-2026-001 ($185,000.00) has been approved and marked as PAID.")
                .type(NotificationType.INVOICE)
                .isRead(false)
                .build();
        notificationRepository.save(notif5);

        Notification notif6 = Notification.builder()
                .user(vendorUser)
                .title("Contractor Profile Active")
                .message("John Contractor is currently allocated to Enterprise Cloud Migration under active billing.")
                .type(NotificationType.SYSTEM)
                .isRead(true)
                .build();
        notificationRepository.save(notif6);

        Notification notif7 = Notification.builder()
                .user(admin)
                .title("System Audit Completed")
                .message("Monthly workforce reconciliation successfully processed with 2 active vendor contracts.")
                .type(NotificationType.SYSTEM)
                .isRead(false)
                .build();
        notificationRepository.save(notif7);

        log.info("Demo database seeding complete! 5 users, 2 vendors, 2 projects, timesheets, milestones, and invoices are ready.");
    }
}
