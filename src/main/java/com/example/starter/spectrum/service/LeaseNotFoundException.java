package com.example.starter.spectrum.service;

import org.springframework.http.HttpStatus;

/**
 * 租约不存在，映射 404。
 */
public class LeaseNotFoundException extends LeaseServiceException {

    public LeaseNotFoundException(long leaseId) {
        super("LEASE_NOT_FOUND", HttpStatus.NOT_FOUND, "lease not found: " + leaseId);
    }
}
