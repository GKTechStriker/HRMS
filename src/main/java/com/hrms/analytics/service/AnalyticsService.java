package com.hrms.analytics.service;

import com.hrms.analytics.dto.AttendanceTrendDto;
import com.hrms.analytics.dto.DepartmentHeadcountDto;
import com.hrms.analytics.dto.LeaveUsageDto;
import com.hrms.attendance.entity.AttendanceStatus;
import com.hrms.attendance.repository.AttendanceRecordRepository;
import com.hrms.employee.entity.EmployeeStatus;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.leave.entity.LeaveStatus;
import com.hrms.leave.repository.LeaveRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service providing aggregated metrics and time-series trend data
 * for management dashboards and visual analytics charts.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private final EmployeeRepository employeeRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public AnalyticsService(EmployeeRepository employeeRepository,
                            AttendanceRecordRepository attendanceRecordRepository,
                            LeaveRequestRepository leaveRequestRepository) {
        this.employeeRepository = employeeRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    /**
     * Retrieve active employee headcount aggregated by department.
     */
    public List<DepartmentHeadcountDto> getDepartmentHeadcount() {
        List<Object[]> rows = employeeRepository.countHeadcountByDepartment(EmployeeStatus.ACTIVE);
        List<DepartmentHeadcountDto> results = new ArrayList<>();
        for (Object[] row : rows) {
            String deptName = (String) row[0];
            long count = ((Number) row[1]).longValue();
            results.add(new DepartmentHeadcountDto(deptName, count));
        }
        return results;
    }

    /**
     * Retrieve daily attendance status trends over the past N days.
     * Ensures every day in the time-series has a continuous data point.
     *
     * @param days number of historical days to inspect (capped between 1 and 60)
     */
    public List<AttendanceTrendDto> getAttendanceTrends(int days) {
        int window = days <= 0 ? 14 : Math.min(days, 60);
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(window - 1);

        Map<LocalDate, AttendanceTrendDto> trendMap = new LinkedHashMap<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            trendMap.put(d, new AttendanceTrendDto(d, 0, 0, 0, 0, 0, 0));
        }

        List<Object[]> rows = attendanceRecordRepository.countAttendanceByDateAndStatus(startDate);
        for (Object[] row : rows) {
            LocalDate date = (LocalDate) row[0];
            AttendanceStatus status = (AttendanceStatus) row[1];
            long count = ((Number) row[2]).longValue();

            AttendanceTrendDto dto = trendMap.get(date);
            if (dto != null && status != null) {
                switch (status) {
                    case PRESENT -> dto.setPresent(dto.getPresent() + count);
                    case LATE -> dto.setLate(dto.getLate() + count);
                    case HALF_DAY -> dto.setHalfDay(dto.getHalfDay() + count);
                    case ABSENT -> dto.setAbsent(dto.getAbsent() + count);
                    case ON_LEAVE -> dto.setOnLeave(dto.getOnLeave() + count);
                    case HOLIDAY -> dto.setHoliday(dto.getHoliday() + count);
                }
            }
        }

        return new ArrayList<>(trendMap.values());
    }

    /**
     * Retrieve approved leave consumption aggregated across leave categories.
     */
    public List<LeaveUsageDto> getLeaveUsage() {
        List<Object[]> rows = leaveRequestRepository.sumDaysByLeaveType(LeaveStatus.APPROVED);
        List<LeaveUsageDto> results = new ArrayList<>();
        for (Object[] row : rows) {
            String leaveType = (String) row[0];
            BigDecimal sum = row[1] instanceof BigDecimal bd ? bd :
                    (row[1] != null ? BigDecimal.valueOf(((Number) row[1]).doubleValue()) : BigDecimal.ZERO);
            results.add(new LeaveUsageDto(leaveType, sum));
        }
        return results;
    }
}
