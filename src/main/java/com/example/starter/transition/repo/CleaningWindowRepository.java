package com.example.starter.transition.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 停机清洗资源窗口仓储：仅停机清洗方式登记，直切不得占用。
 * 唯一索引 (paper_machine, active_flag) 保证同一纸机清洗窗口不重叠。
 */
@Repository
public class CleaningWindowRepository {

    private final JdbcTemplate jdbc;

    public CleaningWindowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在同一事务内登记清洗窗口占用；与改品种执行一起提交/回滚。
     *
     * @return true 登记成功；false 窗口冲突（同机已有占用）
     */
    public boolean tryOccupy(String windowNo, String paperMachine, String orderNo,
                             LocalDateTime now) {
        try {
            jdbc.update("""
                            INSERT INTO cleaning_window
                                (window_no, paper_machine, order_no, occupy_status, active_flag, occupied_at)
                            VALUES (?, ?, ?, 'OCCUPIED', 'ACTIVE', ?)
                            """,
                    windowNo, paperMachine, orderNo, Timestamp.valueOf(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void release(String orderNo, LocalDateTime now) {
        jdbc.update("""
                        UPDATE cleaning_window
                        SET occupy_status = 'RELEASED', active_flag = NULL, released_at = ?
                        WHERE order_no = ? AND active_flag = 'ACTIVE'
                        """,
                Timestamp.valueOf(now), orderNo);
    }

    public long countActiveForOrder(String orderNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM cleaning_window WHERE order_no = ? AND active_flag = 'ACTIVE'",
                Long.class, orderNo);
        return count == null ? 0 : count;
    }

    public long countActiveForMachine(String paperMachine) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM cleaning_window WHERE paper_machine = ? AND active_flag = 'ACTIVE'",
                Long.class, paperMachine);
        return count == null ? 0 : count;
    }
}
