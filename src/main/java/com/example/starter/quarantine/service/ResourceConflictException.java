package com.example.starter.quarantine.service;

/**
 * 资源冲突（HTTP 409）：耳标段已存在有效证等，非幂等键冲突的唯一性竞争。
 */
public class ResourceConflictException extends RuntimeException {

    public ResourceConflictException(String message) {
        super(message);
    }
}
