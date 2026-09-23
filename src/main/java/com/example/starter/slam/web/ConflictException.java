package com.example.starter.slam.web;

/**
 * 同 ID 或同 requestId 改参等冲突，映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
