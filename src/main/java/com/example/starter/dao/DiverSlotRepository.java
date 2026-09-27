package com.example.starter.dao;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 潜水员级别潜水占用槽访问。
 *
 * <p>{@code diver_code} 上有唯一索引：实际下潜时为两名组员各占一行，
 * 两组共享任一潜水员并发下潜时，数据库保证最多一组插入成功（余者抛唯一冲突）。
 * 出水同事务释放两行。</p>
 */
@Repository
public class DiverSlotRepository {

    private final JdbcTemplate jdbc;

    public DiverSlotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 占用潜水员槽；并发下唯一索引保证每个潜水员同时刻只被一个受控潜次占用。
     *
     * @throws DuplicateKeyException 该潜水员已被另一组占用
     */
    public void occupy(String diverCode, String diveRequestId, Instant now) {
        jdbc.update("INSERT INTO diver_dive_slot (diver_code, dive_request_id, created_at_utc) "
                        + "VALUES (?, ?, ?)",
                diverCode, diveRequestId, Timestamp.from(now));
    }

    /** 出水释放该受控潜次占用的全部潜水员槽。 */
    public int releaseByDive(String diveRequestId) {
        return jdbc.update("DELETE FROM diver_dive_slot WHERE dive_request_id = ?", diveRequestId);
    }

    public int countByDiver(String diverCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM diver_dive_slot WHERE diver_code = ?",
                Integer.class, diverCode);
        return count == null ? 0 : count;
    }
}
