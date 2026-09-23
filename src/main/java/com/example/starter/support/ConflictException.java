package com.example.starter.support;

/**
 * 状态冲突：版本不符、指纹冲突、超额占用、非法状态迁移或时间倒退，映射为 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
