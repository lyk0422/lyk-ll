package com.example.starter.rebate.support;

/**
 * 请求引用的资源不存在（批次/单证/疑点等）。
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
