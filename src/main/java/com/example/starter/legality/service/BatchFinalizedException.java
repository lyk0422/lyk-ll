package com.example.starter.legality.service;

/**
 * 批次已封板（终态）后仍尝试报到、重新校验或改写结论。映射 HTTP 409。
 * 封板后改签须走新校验批次。
 */
public class BatchFinalizedException extends RuntimeException {

    public BatchFinalizedException(String batchId) {
        super("batch already finalized: " + batchId);
    }
}
