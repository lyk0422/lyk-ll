package com.example.starter.pointcloud.service;

/**
 * 同 chunkId 但请求参数/内容与已存在块不一致，映射 HTTP 409。
 */
public class ChunkConflictException extends RuntimeException {

    public ChunkConflictException(String message) {
        super(message);
    }
}
