package com.example.starter.slam.web;

/**
 * 请求与已有记录的同 ID / requestId 内容冲突，映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
