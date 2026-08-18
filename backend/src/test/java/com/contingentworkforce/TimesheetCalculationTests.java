package com.contingentworkforce;

import com.contingentworkforce.dto.contractor.ContractorResponse;
import com.contingentworkforce.dto.timesheet.TimesheetRequest;
import com.contingentworkforce.dto.timesheet.TimesheetResponse;
import com.contingentworkforce.entity.*;
import com.contingentworkforce.enums.*;
import com.contingentworkforce.exception.BadRequestException;
import com.contingentworkforce.repository.*;
import com.contingentworkforce.service.ApprovalService;
import com.contingentworkforce.service.NotificationService;
import com.contingentworkforce.service.impl.ContractorServiceImpl;
import com.contingentworkforce.service.impl.TimesheetServiceImpl;
import com.contingentworkforce.service.validation.TimesheetValidationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class TimesheetCalculationTests {

    @Mock
    private TimesheetRepository timesheetRepository;
    @Mock
    private ContractorRepository contractorRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private MilestoneRepository milestoneRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private VendorRepository vendorRepository;
    @Mock
    private ContractorServiceImpl contractorService;
    @Mock
    private ApprovalService approvalService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private TimesheetValidationEngine validationEngine;

    @InjectMocks
    private TimesheetServiceImpl timesheetService;

    private User testUser;
    private Vendor testVendor;
    private Contractor testContractor;
    private Project testProject;
    private UUID contractorId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        contractorId = UUID.randomUUID();
        projectId = UUID.randomUUID();

        testUser = User.builder()
                .id(UUID.randomUUID())
                .name("Test Contractor")
                .email("contractor@test.com")
                .role(Role.CONTRACTOR)
                .status(UserStatus.ACTIVE)
                .build();

        testVendor = Vendor.builder()
                .id(UUID.randomUUID())
                .vendorName("Test Vendor")
                .status(VendorStatus.ACTIVE)
                .build();

        testContractor = Contractor.builder()
                .id(contractorId)
                .user(testUser)
                .vendor(testVendor)
                .jobRole("Senior Engineer")
                .hourlyRate(BigDecimal.valueOf(500.00))
                .status(ContractorStatus.ACTIVE)
                .build();

        testProject = Project.builder()
                .id(projectId)
                .projectName("Test Project")
                .status(ProjectStatus.ACTIVE)
                .build();

        org.mockito.Mockito.lenient().when(contractorService.mapToContractorResponse(any()))
                .thenReturn(ContractorResponse.builder().id(contractorId).build());
    }

    @Test
    @DisplayName("Should correctly calculate 8.00 total hours for 09:00 - 18:00 with 1.00 hr break")
    void testStandardEightHoursCalculation() {
        TimesheetRequest request = TimesheetRequest.builder()
                .contractorId(contractorId)
                .projectId(projectId)
                .workDate(LocalDate.now())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .breakHours(BigDecimal.valueOf(1.00))
                .description("Worked on core features")
                .build();

        when(contractorRepository.findById(contractorId)).thenReturn(Optional.of(testContractor));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(testProject));
        when(timesheetRepository.findByContractorIdAndProjectIdAndWorkDate(any(), any(), any())).thenReturn(Optional.empty());
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> {
            Timesheet saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        TimesheetResponse response = timesheetService.createTimesheet(request);

        assertNotNull(response);
        assertEquals(0, BigDecimal.valueOf(8.00).compareTo(response.getTotalHours()));
        assertEquals(TimesheetStatus.DRAFT, response.getStatus());
    }

    @Test
    @DisplayName("Should correctly calculate 8.00 total hours for 09:00 - 17:30 with 0.50 hr break")
    void testFractionalBreakHoursCalculation() {
        TimesheetRequest request = TimesheetRequest.builder()
                .contractorId(contractorId)
                .projectId(projectId)
                .workDate(LocalDate.now())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 30))
                .breakHours(BigDecimal.valueOf(0.50))
                .description("Worked on bug fixes")
                .build();

        when(contractorRepository.findById(contractorId)).thenReturn(Optional.of(testContractor));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(testProject));
        when(timesheetRepository.findByContractorIdAndProjectIdAndWorkDate(any(), any(), any())).thenReturn(Optional.empty());
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TimesheetResponse response = timesheetService.createTimesheet(request);

        assertNotNull(response);
        assertEquals(0, BigDecimal.valueOf(8.00).compareTo(response.getTotalHours()));
    }

    @Test
    @DisplayName("Should throw BadRequestException when end time is before start time")
    void testEndTimeBeforeStartTimeThrowsException() {
        TimesheetRequest request = TimesheetRequest.builder()
                .contractorId(contractorId)
                .projectId(projectId)
                .workDate(LocalDate.now())
                .startTime(LocalTime.of(18, 0))
                .endTime(LocalTime.of(9, 0))
                .breakHours(BigDecimal.ZERO)
                .build();

        when(contractorRepository.findById(contractorId)).thenReturn(Optional.of(testContractor));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(testProject));

        assertThrows(BadRequestException.class, () -> timesheetService.createTimesheet(request));
    }

    @Test
    @DisplayName("Should throw BadRequestException when break hours is negative")
    void testNegativeBreakHoursThrowsException() {
        TimesheetRequest request = TimesheetRequest.builder()
                .contractorId(contractorId)
                .projectId(projectId)
                .workDate(LocalDate.now())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .breakHours(BigDecimal.valueOf(-1.0))
                .build();

        when(contractorRepository.findById(contractorId)).thenReturn(Optional.of(testContractor));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(testProject));

        assertThrows(BadRequestException.class, () -> timesheetService.createTimesheet(request));
    }

    @Test
    @DisplayName("Should throw BadRequestException when break hours exceeds working duration")
    void testBreakHoursExceedsDurationThrowsException() {
        TimesheetRequest request = TimesheetRequest.builder()
                .contractorId(contractorId)
                .projectId(projectId)
                .workDate(LocalDate.now())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(12, 0)) // 3 hours duration
                .breakHours(BigDecimal.valueOf(4.0)) // 4 hours break
                .build();

        when(contractorRepository.findById(contractorId)).thenReturn(Optional.of(testContractor));
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(testProject));

        assertThrows(BadRequestException.class, () -> timesheetService.createTimesheet(request));
    }
}
