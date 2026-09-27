package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 跑道级互斥锁：在事务内先确保 runway_mutex 行存在，再 SELECT ... FOR UPDATE 串行化同跑道写操作。
 * H2（MODE=MySQL）与 MySQL/InnoDB 均支持 FOR UPDATE 行锁；跨跑道锁定不同行，互不阻塞。
 */
@Repository
public class RunwayMutexRepository {

    private final JdbcTemplate jdbc;

    public RunwayMutexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 锁定跑道行（事务提交/回滚后自动释放）；首次访问时幂等创建互斥行。
     * 使用 queryForList 强制驱动执行查询并在当前事务连接上持有行锁直到事务结束。
     */
    public void lockRunway(String runwayId) {
        jdbc.update("MERGE INTO runway_mutex (runway_id) KEY (runway_id) VALUES (?)", runwayId);
        jdbc.queryForList("SELECT runway_id FROM runway_mutex WHERE runway_id = ? FOR UPDATE",
                String.class, runwayId);
    }
}
