package com.example.starter.slam.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 图版本元数据访问；单行表，FOR UPDATE 行锁串行化图写入与任务提交。
 */
@Repository
public class GraphMetaRepository {

    private final JdbcTemplate jdbc;

    public GraphMetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 锁住元数据行并返回当前图版本（单位：有效写入次数，初始 0）。必须在事务内调用。
     */
    public int lockAndGetCurrentVersion() {
        Integer version = jdbc.queryForObject(
                "SELECT current_version FROM graph_meta WHERE id = 1 FOR UPDATE", Integer.class);
        return version == null ? 0 : version;
    }

    public int currentVersion() {
        Integer version = jdbc.queryForObject(
                "SELECT current_version FROM graph_meta WHERE id = 1", Integer.class);
        return version == null ? 0 : version;
    }

    /**
     * 图版本 +1（仅关键帧与有效边写入时调用；REJECTED 回环不调用）。
     */
    public void incrementVersion() {
        int updated = jdbc.update("UPDATE graph_meta SET current_version = current_version + 1 WHERE id = 1");
        if (updated != 1) {
            throw new IllegalStateException("graph_meta row missing");
        }
    }
}
