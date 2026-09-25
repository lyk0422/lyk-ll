package com.example.starter.pension.web;

/**
 * 同键异参等冲突，映射为 HTTP 409，且不产生任何新指令。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
