package com.example.starter.spectrum.service;

import org.springframework.http.HttpStatus;

/**
 * 乐观版本不匹配，映射 409。
 */
public class VersionConflictException extends LeaseServiceException {

    public VersionConflictException(long leaseId, Integer expected, int actual) {
        super("VERSION_CONFLICT", HttpStatus.CONFLICT,
                "version conflict for lease " + leaseId + ": expected " + expected + ", actual " + actual);
    }
}
