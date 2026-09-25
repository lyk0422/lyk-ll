package com.example.starter.fermentation.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 降温指令仓储：指令行与曲线版本切换在同一事务内写入。
 */
@Repository
public class CoolingRepository {

    private final JdbcClient jdbc;

    public CoolingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入降温指令，返回指令主键；requestId 唯一键兜底幂等。
     */
    public long insert(long batchId, Instant commandAt, Integer fromVersion, int toVersion, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO cooling_command
                            (batch_id, command_at, from_version, to_version, request_id)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .param(batchId)
                .param(Timestamp.from(commandAt))
                .param(fromVersion)
                .param(toVersion)
                .param(requestId)
                .update(keyHolder, "id");
        return keyHolder.getKey().longValue();
    }
}
