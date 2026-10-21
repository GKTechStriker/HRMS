package com.hrms.attendance;

import com.hrms.attendance.dto.AttendanceCorrectionDto;
import com.hrms.attendance.entity.AttendanceRecord;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.entity.WorkMode;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.attendance.service.AttendanceService;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.BusinessException;
import com.hrms.security.entity.User;
import com.hrms.security.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AttendanceServiceTest {

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private AttendanceRecordRepository attendanceRecordRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Clock-in and clock-out flow calculates duration and prevents duplicate punch")
    void testClockInOutFlow() {
        // Find seeded employee Aarav Mehta (EMP-001) linked to employee user
        User employeeUser = userRepository.findByEmail("emp1@company.com")
                .orElseThrow(() -> new IllegalStateException("Seed employee user not found"));

        Employee employee = employeeRepository.findByUserId(employeeUser.getId())
                .orElseThrow(() -> new IllegalStateException("Seed employee profile not found"));

        // 1. Clock In
        AttendanceRecord punchedIn = attendanceService.clockIn(
                employeeUser.getId(), WorkMode.ON_SITE, "127.0.0.1", "Testing clock-in");

        assertNotNull(punchedIn.getId());
        assertEquals(LocalDate.now(), punchedIn.getWorkDate());
        assertNotNull(punchedIn.getClockIn());
        assertNull(punchedIn.getClockOut());
        assertEquals(WorkMode.ON_SITE, punchedIn.getWorkMode());

        // 2. Duplicate clock-in should throw BusinessException
        assertThrows(BusinessException.class, () ->
                attendanceService.clockIn(employeeUser.getId(), WorkMode.ON_SITE, "127.0.0.1", "Duplicate")
        );

        // 3. Clock Out
        AttendanceRecord punchedOut = attendanceService.clockOut(
                employeeUser.getId(), "127.0.0.1", "End of test shift");

        assertNotNull(punchedOut.getClockOut());
        assertNotNull(punchedOut.getDurationMinutes());
        assertTrue(punchedOut.getDurationMinutes() >= 0);

        // 4. Duplicate clock-out should throw BusinessException
        assertThrows(BusinessException.class, () ->
                attendanceService.clockOut(employeeUser.getId(), "127.0.0.1", "Duplicate out")
        );
    }

    @Test
    @DisplayName("HR manual correction properly adjusts status, times and sets audit flags")
    void testAttendanceCorrection() {
        User hrUser = userRepository.findByEmail("hr1@company.com")
                .orElseThrow(() -> new IllegalStateException("Seed HR user not found"));

        User employeeUser = userRepository.findByEmail("emp1@company.com")
                .orElseThrow(() -> new IllegalStateException("Seed employee user not found"));

        Employee employee = employeeRepository.findByUserId(employeeUser.getId())
                .orElseThrow(() -> new IllegalStateException("Seed employee profile not found"));

        // Create initial record
        AttendanceRecord initial = new AttendanceRecord(employee, LocalDate.now().minusDays(1), AttendanceStatus.ABSENT, WorkMode.ON_SITE);
        attendanceRecordRepository.save(initial);

        // Prepare correction DTO
        AttendanceCorrectionDto dto = new AttendanceCorrectionDto();
        dto.setRecordId(initial.getId());
        dto.setEmployeeId(employee.getId());
        dto.setWorkDate(LocalDate.now().minusDays(1));
        dto.setStatus(AttendanceStatus.PRESENT);
        dto.setWorkMode(WorkMode.REMOTE);
        dto.setClockInTime("09:05");
        dto.setClockOutTime("18:00");
        dto.setReason("Employee worked remote on approved project task; system missed automated sync");

        AttendanceRecord corrected = attendanceService.correctAttendance(dto, hrUser.getId());

        assertEquals(AttendanceStatus.PRESENT, corrected.getStatus());
        assertEquals(WorkMode.REMOTE, corrected.getWorkMode());
        assertTrue(corrected.isManuallyCorrected());
        assertEquals(hrUser.getId(), corrected.getCorrectedBy().getId());
        assertEquals(535, corrected.getDurationMinutes()); // 09:05 to 18:00 = 8h 55m = 535m
        assertEquals("Employee worked remote on approved project task; system missed automated sync", corrected.getCorrectionReason());
    }
}
