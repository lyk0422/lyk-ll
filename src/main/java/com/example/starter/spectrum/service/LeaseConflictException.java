package com.example.starter.spectrum.service;

import com.example.starter.spectrum.domain.ConflictReport;
import org.springframework.http.HttpStatus;

/**
 * 候选租约与当前活动租约集合冲突（独占冲突或共享片段功率超限），映射 409，
 * 响应体携带冲突解释报告。
 */
public class LeaseConflictException extends LeaseServiceException {

    private final transient ConflictReport report;

    public LeaseConflictException(ConflictReport report) {
        super("LEASE_CONFLICT", HttpStatus.CONFLICT, "lease conflicts with active leases");
        this.report = report;
    }

    public ConflictReport getReport() {
        return report;
    }
}
