package com.example.starter.museum.repo;

import com.example.starter.museum.domain.po.EnvironmentReadingPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 温湿度读数仓储。唯一索引 (showcase_id, sample_time) 做物理去重；
 * 判异时按 sample_time 升序归位，乱序上报不影响连续 3 点判定。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReadingRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record InsertResult(boolean inserted, Long id) {
    }

    /** 插入读数；重复（同展柜同采样时刻）返回 inserted=false。 */
    public InsertResult insert(Long showcaseId, Instant sampleTime,
                               java.math.BigDecimal temperature,
                               java.math.BigDecimal humidity,
                               boolean late) {
        try {
            jdbc.update("""
                    INSERT INTO environment_reading
                      (showcase_id, sample_time, temperature, humidity, received_at, late)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, showcaseId, Sql.ts(sampleTime), temperature, humidity,
                    Sql.ts(clock.instant()), late);
        } catch (DuplicateKeyException e) {
            return new InsertResult(false, null);
        }
        Long id = jdbc.queryForObject(
                "SELECT id FROM environment_reading WHERE showcase_id = ? AND sample_time = ?",
                Long.class, showcaseId, Sql.ts(sampleTime));
        return new InsertResult(true, id);
    }

    public Optional<EnvironmentReadingPo> find(Long showcaseId, Instant sampleTime) {
        return jdbc.query(
                        "SELECT * FROM environment_reading WHERE showcase_id = ? AND sample_time = ?",
                        RowMappers.READING, showcaseId, Sql.ts(sampleTime))
                .stream().findFirst();
    }

    /** 取截至某时刻（含）最近的若干个非迟到读数，按采样时刻归位。 */
    public List<EnvironmentReadingPo> recentOrdered(Long showcaseId, Instant upTo, int limit) {
        return jdbc.query("""
                        SELECT * FROM (
                            SELECT * FROM environment_reading
                            WHERE showcase_id = ? AND sample_time <= ? AND late = FALSE
                            ORDER BY sample_time DESC, id DESC
                            LIMIT ?
                        ) t ORDER BY sample_time ASC
                        """,
                RowMappers.READING, showcaseId, Sql.ts(upTo), limit);
    }

    public List<EnvironmentReadingPo> findAllOrdered(Long showcaseId) {
        return jdbc.query(
                "SELECT * FROM environment_reading WHERE showcase_id = ? ORDER BY sample_time ASC, id ASC",
                RowMappers.READING, showcaseId);
    }

    /** 展柜最新一个非迟到读数（按采样时刻归位）。 */
    public Optional<EnvironmentReadingPo> findLatest(Long showcaseId) {
        return jdbc.query(
                        "SELECT * FROM environment_reading WHERE showcase_id = ? AND late = FALSE "
                                + "ORDER BY sample_time DESC, id DESC LIMIT 1",
                        RowMappers.READING, showcaseId)
                .stream().findFirst();
    }
}
