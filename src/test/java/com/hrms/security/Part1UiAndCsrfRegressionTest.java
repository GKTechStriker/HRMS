package com.hrms.security;

import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.WorkMode;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.leave.dto.LeaveApplicationDto;
import com.hrms.leave.entity.LeaveBalance;
import com.hrms.leave.entity.LeaveRequest;
import com.hrms.leave.entity.LeaveStatus;
import com.hrms.leave.entity.LeaveType;
import com.hrms.leave.repository.LeaveBalanceRepository;
import com.hrms.leave.repository.LeaveRequestRepository;
import com.hrms.leave.repository.LeaveTypeRepository;
import com.hrms.leave.service.LeaveBalanceService;
import com.hrms.leave.service.LeaveService;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end MockMvc & Thymeleaf template regression tests for:
 * - BUG-01: Rendering leaves/my, leaves/manage, attendance/my, dashboard, audit, sessions with populated timestamps
 * - BUG-02: Presence of single CSRF token inside #rejectForm and successful POST /leaves/{id}/reject flow
 * - BUG-03: Null durationMinutes / ON_LEAVE null punch rendering in attendance/my and dashboard
 */
@SpringBootTest
class Part1UiAndCsrfRegressionTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveBalanceService leaveBalanceService;

    @Autowired
    private LeaveRequestRepository leaveRequestRepository;

    @Autowired
    private LeaveBalanceRepository leaveBalanceRepository;

    @Autowired
    private LeaveTypeRepository leaveTypeRepository;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Clock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("BUG-01 & BUG-02: leaves/manage and leaves/my render Instant timestamps and #rejectForm submits cleanly with CSRF")
    @WithUserDetails("hr1@company.com")
    void leavePagesRenderTimestampsAndRejectFormWorksWithCsrf() throws Exception {
        User empUser = userRepository.findByEmail("emp1@company.com").orElseThrow();
        Employee emp = employeeRepository.findByUserId(empUser.getId()).orElseThrow();
        LeaveType annualType = leaveTypeRepository.findByCode("ANNUAL").orElseThrow();

        LocalDate startDate = LocalDate.now(clock).plusWeeks(10);
        while (startDate.getDayOfWeek() != DayOfWeek.MONDAY) {
            startDate = startDate.plusDays(1);
        }
        final int targetYear = startDate.getYear();
        leaveBalanceService.initializeBalancesForEmployee(emp, targetYear);

        LeaveApplicationDto dto = new LeaveApplicationDto();
        dto.setLeaveTypeId(annualType.getId());
        dto.setStartDate(startDate);
        dto.setEndDate(startDate);
        dto.setReason("UI & CSRF verification leave");

        LeaveRequest pending = leaveService.applyForLeave(empUser.getId(), dto);
        try {
            // Render leaves/manage with real PENDING record (exercises createdAt @timeFmt.formatInstant and #rejectForm)
            MvcResult manageRes = mockMvc.perform(get("/leaves/manage"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"rejectForm\"")))
                    .andExpect(content().string(containsString("data-action-prefix=\"/leaves/\"")))
                    .andExpect(content().string(containsString("name=\"_csrf\"")))
                    .andReturn();

            String html = manageRes.getResponse().getContentAsString();
            assertFalse(html.contains("Internal Server Error"), "Rendered page must not contain server error text");
            assertFalse(html.contains("Whitelabel Error Page"), "Rendered page must not hit error page");

            // POST without CSRF must fail with 403 Forbidden
            mockMvc.perform(post("/leaves/" + pending.getId() + "/reject")
                            .param("rejectionReason", "Missing CSRF test"))
                    .andExpect(status().isForbidden());

            // POST with CSRF succeeds cleanly and releases provisional hold
            mockMvc.perform(post("/leaves/" + pending.getId() + "/reject")
                            .with(csrf())
                            .param("rejectionReason", "Sprint crunch deadline"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/leaves/manage"));

            LeaveRequest reloaded = leaveRequestRepository.findById(pending.getId()).orElseThrow();
            assertEquals(LeaveStatus.REJECTED, reloaded.getStatus());
            assertEquals("Sprint crunch deadline", reloaded.getRejectionReason());

            LeaveBalance balanceAfter = leaveBalanceRepository
                    .findByEmployeeIdAndLeaveTypeIdAndYear(emp.getId(), annualType.getId(), targetYear)
                    .orElseThrow();
            BigDecimal total = balanceAfter.getRemainingDays()
                    .add(balanceAfter.getUsedDays())
                    .add(balanceAfter.getPendingDays());
            assertEquals(0, balanceAfter.getAllocatedDays().compareTo(total));

            // Render audit & sessions oversight views too
            mockMvc.perform(get("/audit")).andExpect(status().isOk());
            mockMvc.perform(get("/sessions")).andExpect(status().isOk());
            mockMvc.perform(get("/attendance/manage")).andExpect(status().isOk());
        } finally {
            leaveRequestRepository.findById(pending.getId()).ifPresent(req -> {
                if (req.getStatus() == LeaveStatus.PENDING) {
                    leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(
                            emp.getId(), annualType.getId(), targetYear)
                            .ifPresent(bal -> {
                                bal.setPendingDays(bal.getPendingDays().subtract(req.getTotalDays()).max(BigDecimal.ZERO));
                                bal.setRemainingDays(bal.getAllocatedDays().subtract(bal.getUsedDays()).subtract(bal.getPendingDays()));
                                leaveBalanceRepository.save(bal);
                            });
                }
                leaveRequestRepository.delete(req);
            });
        }
    }

    @Test
    @DisplayName("BUG-01 & BUG-03: Employee views render cleanly with null durationMinutes and ON_LEAVE null punches")
    @WithUserDetails("emp1@company.com")
    void employeeViewsRenderNullDurationAndOnLeaveSafely() throws Exception {
        User empUser = userRepository.findByEmail("emp1@company.com").orElseThrow();
        Employee emp = employeeRepository.findByUserId(empUser.getId()).orElseThrow();
        LocalDate today = LocalDate.now(clock);

        AttendanceRecord originalToday = attendanceRecordRepository.findByEmployeeIdAndWorkDate(emp.getId(), today).orElse(null);
        try {
            if (originalToday != null) {
                attendanceRecordRepository.delete(originalToday);
                attendanceRecordRepository.flush();
            }

            // Case 1: Completed shift with null durationMinutes (manual correction with missing clockIn)
            AttendanceRecord nullDurationRec = new AttendanceRecord(emp, today, AttendanceStatus.HALF_DAY, WorkMode.REMOTE);
            nullDurationRec.setClockOut(OffsetDateTime.now(clock));
            nullDurationRec.setDurationMinutes(null);
            nullDurationRec = attendanceRecordRepository.saveAndFlush(nullDurationRec);

            mockMvc.perform(get("/attendance/my"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Day Complete")));
            mockMvc.perform(get("/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Shift completed")));

            // Case 2: ON_LEAVE record (clockIn == null && clockOut == null)
            attendanceRecordRepository.delete(nullDurationRec);
            attendanceRecordRepository.flush();

            AttendanceRecord onLeaveRec = new AttendanceRecord(emp, today, AttendanceStatus.ON_LEAVE, WorkMode.ON_SITE);
            onLeaveRec = attendanceRecordRepository.saveAndFlush(onLeaveRec);

            mockMvc.perform(get("/attendance/my")).andExpect(status().isOk());
            mockMvc.perform(get("/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Today's status:")))
                    .andExpect(content().string(containsString("On Approved Leave")));

            // Case 3: Render /leaves/my with an actual persisted LeaveRequest (exercises @timeFmt.formatInstant on req.createdAt)
            LeaveType annualType = leaveTypeRepository.findByCode("ANNUAL").orElseThrow();
            LocalDate futureMon = today.plusWeeks(12);
            while (futureMon.getDayOfWeek() != DayOfWeek.MONDAY) {
                futureMon = futureMon.plusDays(1);
            }
            leaveBalanceService.initializeBalancesForEmployee(emp, futureMon.getYear());
            LeaveApplicationDto leaveDto = new LeaveApplicationDto();
            leaveDto.setLeaveTypeId(annualType.getId());
            leaveDto.setStartDate(futureMon);
            leaveDto.setEndDate(futureMon);
            leaveDto.setReason("Verify /leaves/my createdAt rendering");
            LeaveRequest createdLeave = leaveService.applyForLeave(empUser.getId(), leaveDto);
            try {
                mockMvc.perform(get("/leaves/my"))
                        .andExpect(status().isOk())
                        .andExpect(content().string(containsString("Verify /leaves/my createdAt rendering")));
            } finally {
                leaveService.cancelLeave(createdLeave.getId(), empUser.getId());
                leaveRequestRepository.deleteById(createdLeave.getId());
            }

            attendanceRecordRepository.delete(onLeaveRec);
            attendanceRecordRepository.flush();
        } finally {
            attendanceRecordRepository.findByEmployeeIdAndWorkDate(emp.getId(), today)
                    .ifPresent(attendanceRecordRepository::delete);
        }
    }
}
