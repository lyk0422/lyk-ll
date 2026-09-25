package com.example.starter.epidemic.service;

/**
 * 同 requestId 并发首请求竞争的语义标记类型。
 * 当前实现通过编程式事务在提交冲突后整体回滚并由编排层重放首个结果，
 * 故该类型暂未抛出；保留以表达领域语义，便于未来在事务内细分冲突来源。
 */
class IdempotentRaceException extends RuntimeException {
    IdempotentRaceException() {
        super("idempotent race");
    }
}
