package com.example.starter.slam.opt;

/**
 * 优化器可重试失败：任务转入 RETRYABLE_FAILED，后续重试必须使用原快照。
 */
public class RetryableOptimizationException extends RuntimeException {

    public RetryableOptimizationException(String message) {
        super(message);
    }

    public RetryableOptimizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
