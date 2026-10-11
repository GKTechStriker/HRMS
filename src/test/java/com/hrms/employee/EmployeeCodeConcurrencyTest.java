package com.hrms.employee;

import com.hrms.employee.service.EmployeeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Milestone 2 Concurrency Test:
 * Verifies that concurrent employee code allocations (EMP-xxx) via PostgreSQL sequence
 * produce 100% unique codes with zero collisions.
 */
@SpringBootTest
class EmployeeCodeConcurrencyTest {

    @Autowired
    private EmployeeService employeeService;

    @Test
    void concurrentEmployeeCodeAllocationProducesZeroDuplicates() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        Set<String> allocatedCodes = ConcurrentHashMap.newKeySet();
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    String code = employeeService.allocateNextEmployeeCode();
                    allocatedCodes.add(code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(threads, allocatedCodes.size(),
                "Every concurrent thread must receive a distinct EMP-xxx code.");
    }
}
