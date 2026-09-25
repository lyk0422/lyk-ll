package com.example.starter.fermentation.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 温度采样读数仓储：逐点持久化目标温度、偏离量与连续偏离计数，供告警口径复算。
 */
@Repository
public class TemperatureRepository {

    private final JdbcClient jdbc;

    public TemperatureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条温度读数。
     */
    public void insert(long batchId,
                       Instant readingAt,
                       String stage,
                       BigDecimal targetTemp,
                       BigDecimal temperature,
                       BigDecimal deviation,
                       boolean outOfRange,
                       int runLength,
                       boolean alarmed) {
        jdbc.sql("""
                        INSERT INTO temperature_reading
                            (batch_id, reading_at, stage, target_temp, temperature,
                             deviation, out_of_range, run_length, alarmed)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(batchId)
                .param(Timestamp.from(readingAt))
                .param(stage)
                .param(targetTemp)
                .param(temperature)
                .param(deviation)
                .param(outOfRange)
                .param(runLength)
                .param(alarmed)
                .update();
    }

    /**
     * 查询本阶段（主酵/后熟）截至指定时刻最近一条读数的连续偏离点数；无记录返回 0。
     * 连续口径按采样时间排序，只看时间不早于当前点的同一批次读数。
     */
    public int latestRunLengthBefore(long batchId, String stage, Instant readingAt) {
        return jdbc.sql("""
                        SELECT COALESCE(run_length, 0)
                        FROM temperature_reading
                        WHERE batch_id = ? AND stage = ? AND reading_at < ?
                        ORDER BY reading_at DESC, id DESC
                        LIMIT 1
                        """)
                .param(batchId)
                .param(stage)
                .param(Timestamp.from(readingAt))
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /**
     * 统计某阶段读数数量，用于复算验证。
     */
    public int countByStage(long batchId, String stage) {
        return jdbc.sql("SELECT COUNT(*) FROM temperature_reading WHERE batch_id = ? AND stage = ?")
                .param(batchId)
                .param(stage)
                .query(Integer.class)
                .single();
    }

    /**
     * 查询某阶段 [from, to] 时间窗内读数（端点均含），供后熟 -1℃ 保温口径复算。
     */
    public List<ReadingWindowRow> findInWindow(long batchId, String stage,
                                               Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT reading_at, target_temp, temperature, out_of_range, alarmed
                        FROM temperature_reading
                        WHERE batch_id = ? AND stage = ? AND reading_at >= ? AND reading_at <= ?
                        ORDER BY reading_at ASC, id ASC
                        """)
                .param(batchId)
                .param(stage)
                .param(Timestamp.from(from))
                .param(Timestamp.from(to))
                .query((rs, n) -> new ReadingWindowRow(
                        rs.getTimestamp("reading_at").toInstant(),
                        rs.getBigDecimal("target_temp"),
                        rs.getBigDecimal("temperature"),
                        rs.getBoolean("out_of_range"),
                        rs.getBoolean("alarmed")))
                .list();
    }

    /**
     * 时间窗内温度读数快照。
     */
    public record ReadingWindowRow(
            Instant readingAt,
            BigDecimal targetTemp,
            BigDecimal temperature,
            boolean outOfRange,
            boolean alarmed) {
    }
}
