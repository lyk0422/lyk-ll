package com.example.starter.perception.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 已处理检测表（processed_detection）：detectionId 幂等去重。
 */
@Repository
public class ProcessedDetectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedDetectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录某条检测的首次处理结果；主键冲突（重复 detectionId）时抛 {@link DuplicateKeyException}，
     * 由服务层转成幂等返回而不是再次应用。
     */
    public void insert(String detectionId, String trackId, boolean accepted, String resultJson,
                       Instant eventTime, Instant processedAt) {
        String sql = "INSERT INTO processed_detection (detection_id, track_id, accepted, result, "
                + "event_time, processed_at) VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, detectionId, trackId, accepted, resultJson,
                Timestamp.from(eventTime), Timestamp.from(processedAt));
    }

    /** 按 detectionId 查询首次处理结果JSON。 */
    public Optional<String> findResultById(String detectionId) {
        List<String> results = jdbcTemplate.query(
                "SELECT result FROM processed_detection WHERE detection_id = ?",
                (rs, rowNum) -> rs.getString("result"), detectionId);
        return results.stream().findFirst();
    }
}
