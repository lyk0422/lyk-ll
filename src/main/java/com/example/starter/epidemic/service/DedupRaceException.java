package com.example.starter.epidemic.service;

/**
 * 并发初报去重竞争落败的语义标记类型。
 * 当前实现通过编程式事务在去重唯一索引冲突（含提交时检出）后整体回滚，
 * 由编排层在事务外重查胜者卡并按 DUPLICATE 重放，故该类型暂未抛出；
 * 保留以表达领域语义，便于未来在事务内细分冲突来源。
 */
class DedupRaceException extends RuntimeException {
    DedupRaceException() {
        super("dedup race");
    }
}
