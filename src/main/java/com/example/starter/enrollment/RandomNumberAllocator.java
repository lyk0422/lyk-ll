package com.example.starter.enrollment;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 随机号分配器：在独立事务（REQUIRES_NEW）中递增中心计数器并立即提交，
 * 因此已发出的随机号即使外层入组事务失败或回滚也不会被重用（允许出现跳号）。
 */
@Component
public class RandomNumberAllocator {

    private final JdbcTemplate jdbc;

    public RandomNumberAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 为中心分配下一个随机号（中心内从 1 开始递增）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long allocate(long trialId, long centerId) {
        int updated = jdbc.update(
                "UPDATE random_counter SET next_no = next_no + 1 WHERE trial_id = ? AND center_id = ?",
                trialId, centerId);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO random_counter (trial_id, center_id, next_no) VALUES (?, ?, 2)",
                        trialId, centerId);
                return 1L;
            } catch (DuplicateKeyException e) {
                // 并发首次分配：另一事务已插入计数器行，改为走递增分支
                jdbc.update(
                        "UPDATE random_counter SET next_no = next_no + 1 WHERE trial_id = ? AND center_id = ?",
                        trialId, centerId);
            }
        }
        Long next = jdbc.queryForObject(
                "SELECT next_no FROM random_counter WHERE trial_id = ? AND center_id = ?",
                Long.class, trialId, centerId);
        return next - 1;
    }
}
