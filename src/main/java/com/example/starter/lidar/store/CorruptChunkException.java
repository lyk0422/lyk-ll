package com.example.starter.lidar.store;

/**
 * 块文件损坏（无法读取、解压或校验和不匹配）时抛出。
 */
public class CorruptChunkException extends RuntimeException {

    public CorruptChunkException(String message) {
        super(message);
    }

    public CorruptChunkException(String message, Throwable cause) {
        super(message, cause);
    }
}
