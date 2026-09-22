package com.example.starter.dbc;

/**
 * 导入/帧请求与当前定义冲突（如异参重放 requestId、版本重复导入、同序号负载不一致）。
 * 映射为 HTTP 409。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
