package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 跑道咨询锁：例行开始与紧急接报事务先对同一跑道行加行锁串行化，
 * 保证“批准/开始 vs 紧急接报”得到唯一占用状态；不同跑道为不同行，互不阻塞。
 */
@Repository
public class RunwayLockRepository {

    private final JdbcTemplate jdbc;

    public RunwayLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 确保跑道锁行存在并对其加排他行锁（FOR UPDATE）。必须在事务内调用，锁持有到事务提交/回滚。
     */
    public void lockRunway(String runwayId) {
        jdbc.update("MERGE INTO runway_lock (runway_id) KEY (runway_id) VALUES (?)", runwayId);
        jdbc.queryForMap("SELECT runway_id FROM runway_lock WHERE runway_id = ? FOR UPDATE", runwayId);
    }
}
