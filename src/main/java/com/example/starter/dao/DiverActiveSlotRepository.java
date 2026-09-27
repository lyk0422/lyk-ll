package com.example.starter.dao;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 潜水员受控下潜占用槽访问。主键即潜水员工号：
 * 两组共享同一潜水员并发下潜时，唯一索引保证最多一组成功插入（败方抛
 * {@link DuplicateKeyException}，由业务转 409 并整体回滚）。
 */
@Repository
public class DiverActiveSlotRepository {

    private final JdbcTemplate jdbc;

    public DiverActiveSlotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 尝试占用潜水员；已被其他受控潜次占用时抛 DuplicateKeyException。 */
    public void acquire(String diverCode, String diveRequestId, int planVersion, Instant now) {
        jdbc.update("INSERT INTO diver_active_slot "
                        + "(diver_code, dive_request_id, plan_version, acquired_at_utc) "
                        + "VALUES (?, ?, ?, ?)",
                diverCode, diveRequestId, planVersion, Timestamp.from(now));
    }

    /** 受控出水原子释放本潜次占用的两名潜水员槽位。 */
    public int releaseByDive(String diveRequestId) {
        return jdbc.update("DELETE FROM diver_active_slot WHERE dive_request_id = ?", diveRequestId);
    }

    public boolean existsForDive(String diveRequestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM diver_active_slot WHERE dive_request_id = ?",
                Integer.class, diveRequestId);
        return count != null && count > 0;
    }
}
