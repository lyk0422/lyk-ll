package com.example.starter.transition.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 纸机执行占用与停机清洗窗口数据访问；占用与窗口在同一事务内登记。
 */
@Repository
public class ResourceRepository {

    private final JdbcTemplate jdbc;

    public ResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 尝试占用纸机执行锁；唯一约束保证同一纸机同时刻只有一张执行中单。 */
    public boolean tryAcquireMachineLock(String machineId, String orderNo) {
        try {
            jdbc.update("INSERT INTO machine_execution_lock (machine_id, order_no) VALUES (?, ?)",
                    machineId, orderNo);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void releaseMachineLock(String machineId) {
        jdbc.update("DELETE FROM machine_execution_lock WHERE machine_id = ?", machineId);
    }

    public void insertCleaningWindow(String machineId, String orderNo) {
        jdbc.update("INSERT INTO cleaning_window (machine_id, order_no, window_start) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP)", machineId, orderNo);
    }

    public void closeCleaningWindow(String orderNo) {
        jdbc.update("UPDATE cleaning_window SET window_end = CURRENT_TIMESTAMP "
                + "WHERE order_no = ? AND window_end IS NULL", orderNo);
    }

    public boolean hasCleaningWindow(String orderNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM cleaning_window WHERE order_no = ?", Integer.class, orderNo);
        return count != null && count > 0;
    }
}
