package com.hrms.leave.service;

import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.exception.ResourceNotFoundException;
import com.hrms.leave.dto.LeaveBalanceSummaryDto;
import com.hrms.leave.entity.LeaveBalance;
import com.hrms.leave.entity.LeaveType;
import com.hrms.leave.repository.LeaveBalanceRepository;
import com.hrms.leave.repository.LeaveTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service managing annual leave quotas and running balances.
 */
@Service
public class LeaveBalanceService {

    private static final Logger log = LoggerFactory.getLogger(LeaveBalanceService.class);

    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final EmployeeRepository employeeRepository;

    public LeaveBalanceService(LeaveBalanceRepository leaveBalanceRepository,
                               LeaveTypeRepository leaveTypeRepository,
                               EmployeeRepository employeeRepository) {
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Initializes annual quotas for all configured leave types for an employee.
     */
    @Transactional
    public List<LeaveBalance> initializeBalancesForEmployee(Employee employee, int year) {
        List<LeaveType> leaveTypes = leaveTypeRepository.findAll();
        List<LeaveBalance> created = new ArrayList<>();

        for (LeaveType type : leaveTypes) {
            boolean exists = leaveBalanceRepository
                    .findByEmployeeIdAndLeaveTypeIdAndYear(employee.getId(), type.getId(), year)
                    .isPresent();

            if (!exists) {
                LeaveBalance balance = new LeaveBalance(
                        employee,
                        type,
                        year,
                        type.getDefaultDaysPerYear() != null ? type.getDefaultDaysPerYear() : BigDecimal.ZERO
                );
                created.add(leaveBalanceRepository.save(balance));
            }
        }

        if (!created.isEmpty()) {
            log.info("Initialized {} leave balance categories for employee {} ({}) for year {}",
                    created.size(), employee.getFullName(), employee.getEmployeeCode(), year);
        }

        return leaveBalanceRepository.findByEmployeeIdAndYearWithLeaveType(employee.getId(), year);
    }

    /**
     * Retrieves leave balances for an employee for a calendar year, auto-initializing if needed.
     */
    @Transactional
    public List<LeaveBalanceSummaryDto> getEmployeeBalances(UUID employeeId, int year) {
        List<LeaveBalance> balances = leaveBalanceRepository.findByEmployeeIdAndYearWithLeaveType(employeeId, year);

        if (balances.isEmpty()) {
            Employee employee = employeeRepository.findById(employeeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Employee not found with ID: " + employeeId));
            balances = initializeBalancesForEmployee(employee, year);
        }

        return balances.stream()
                .map(b -> LeaveBalanceSummaryDto.builder()
                        .balanceId(b.getId())
                        .leaveTypeId(b.getLeaveType().getId())
                        .leaveTypeCode(b.getLeaveType().getCode())
                        .leaveTypeName(b.getLeaveType().getName())
                        .isPaid(b.getLeaveType().isPaid())
                        .allocatedDays(b.getAllocatedDays())
                        .usedDays(b.getUsedDays())
                        .pendingDays(b.getPendingDays())
                        .remainingDays(b.getRemainingDays())
                        .build())
                .toList();
    }
}
