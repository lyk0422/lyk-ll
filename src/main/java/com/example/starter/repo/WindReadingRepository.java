package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 风速实测数据访问；按分段与测量时刻去重。
 */
@Repository
public class WindReadingRepository {

    /** 风速记录行。 */
    public record WindReadingRow(long id, long lineId, long segmentId, LocalDateTime measuredAt,
                                 LocalDateTime minuteBucket, BigDecimal speed, LocalDateTime receivedAt) {
    }

    /** 分钟桶统计：桶内平均风速与样本数。 */
    public record BucketStat(LocalDateTime minuteBucket, BigDecimal avgSpeed, long sampleCount) {
    }

    private final JdbcTemplate jdbc;

    public WindReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long lineId, long segmentId, LocalDateTime measuredAt, LocalDateTime minuteBucket,
                       BigDecimal speed, LocalDateTime receivedAt) {
        jdbc.update("INSERT INTO wind_reading (line_id, segment_id, measured_at, minute_bucket, speed, received_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                lineId, segmentId, Timestamp.valueOf(measuredAt), Timestamp.valueOf(minuteBucket),
                speed, Timestamp.valueOf(receivedAt));
    }

    /** 按分段与测量时刻精确查找（去重键）。 */
    public Optional<WindReadingRow> findBySegmentAndMeasuredAt(long segmentId, LocalDateTime measuredAt) {
        return jdbc.query("SELECT id, line_id, segment_id, measured_at, minute_bucket, speed, received_at "
                        + "FROM wind_reading WHERE segment_id = ? AND measured_at = ?",
                (rs, n) -> map(rs), segmentId, Timestamp.valueOf(measuredAt)).stream().findFirst();
    }

    /**
     * 查询分段在 [start, end] 内各分钟桶的平均风速，按桶升序。
     */
    public List<BucketStat> bucketStats(long segmentId, LocalDateTime start, LocalDateTime end) {
        return jdbc.query("SELECT minute_bucket, AVG(speed) AS avg_speed, COUNT(*) AS cnt FROM wind_reading "
                        + "WHERE segment_id = ? AND minute_bucket BETWEEN ? AND ? "
                        + "GROUP BY minute_bucket ORDER BY minute_bucket",
                (rs, n) -> new BucketStat(rs.getTimestamp("minute_bucket").toLocalDateTime(),
                        rs.getBigDecimal("avg_speed"), rs.getLong("cnt")),
                segmentId, Timestamp.valueOf(start), Timestamp.valueOf(end));
    }

    /** 分段最新测量时刻对应的分钟桶；无数据时为空。 */
    public Optional<LocalDateTime> latestBucket(long segmentId) {
        return jdbc.query("SELECT MAX(minute_bucket) AS mb FROM wind_reading WHERE segment_id = ?",
                (rs, n) -> {
                    Timestamp ts = rs.getTimestamp("mb");
                    return ts == null ? null : ts.toLocalDateTime();
                }, segmentId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public long countBySegment(long segmentId) {
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM wind_reading WHERE segment_id = ?",
                Long.class, segmentId);
        return cnt == null ? 0 : cnt;
    }

    private WindReadingRow map(ResultSet rs) throws SQLException {
        return new WindReadingRow(rs.getLong("id"), rs.getLong("line_id"), rs.getLong("segment_id"),
                rs.getTimestamp("measured_at").toLocalDateTime(),
                rs.getTimestamp("minute_bucket").toLocalDateTime(),
                rs.getBigDecimal("speed"), rs.getTimestamp("received_at").toLocalDateTime());
    }
}
