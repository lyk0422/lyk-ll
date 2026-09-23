package com.example.starter.pointcloud.service;

/**
 * 块文件无法通过校验或无法解析（损坏），需隔离。
 */
public class CorruptChunkException extends RuntimeException {

    public CorruptChunkException(String message) {
        super(message);
    }

    public CorruptChunkException(String message, Throwable cause) {
        super(message, cause);
    }
}
