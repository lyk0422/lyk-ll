package com.example.starter.cems.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 同 requestId 但业务指纹不一致，或唯一约束冲突（如重复时段、重复执行中限排令）。
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
