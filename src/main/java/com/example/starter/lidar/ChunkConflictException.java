package com.example.starter.lidar;

/**
 * 同 chunkId 但内容/参数与已存在块不一致（冲突），映射 HTTP 409。
 */
public class ChunkConflictException extends RuntimeException {

    public ChunkConflictException(String message) {
        super(message);
    }
}
