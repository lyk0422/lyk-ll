package com.example.starter.rosconfig.exception;

/**
 * 404：节点/Schema 不存在。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
