package com.example.starter.rolling.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主键分配：基于 id_alloc 表的原子更新取号，避免跨库自增差异；
 * 每次取号独立事务提交，保证并发调用不重号。
 */
@Repository
public class IdAllocator {

    private static final String SEQ_NAME = "global";

    private final JdbcTemplate jdbc;

    public IdAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 分配一个新主键。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long nextId() {
        return nextIds(1).start();
    }

    /**
     * 一次分配 n 个连续主键，返回 [start, start+n) 区间。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdRange nextIds(int n) {
        int updated = jdbc.update(
                "UPDATE id_alloc SET next_val = next_val + ? WHERE seq_name = ?", n, SEQ_NAME);
        if (updated == 0) {
            throw new IllegalStateException("id_alloc 缺少序列行: " + SEQ_NAME);
        }
        Long after = jdbc.queryForObject(
                "SELECT next_val FROM id_alloc WHERE seq_name = ?", Long.class, SEQ_NAME);
        if (after == null) {
            throw new IllegalStateException("id_alloc 序列行丢失: " + SEQ_NAME);
        }
        return new IdRange(after - n, after);
    }

    /**
     * 连续 ID 区间 [start, end)。
     */
    public record IdRange(long start, long end) {
    }
}
