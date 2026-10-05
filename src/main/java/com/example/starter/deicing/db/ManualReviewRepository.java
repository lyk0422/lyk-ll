package com.example.starter.deicing.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 人工确认队列数据访问：库存不足的两步法申请转入，不静默降级。
 */
@Repository
public class ManualReviewRepository {

    private static final RowMapper<ManualReviewPo> MAPPER = (ResultSet rs, int rowNum) -> map(rs);

    private static ManualReviewPo map(ResultSet rs) throws SQLException {
        ManualReviewPo po = new ManualReviewPo();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setFlightNumber(rs.getString("flight_number"));
        po.setReason(rs.getString("reason"));
        po.setDetail(rs.getString("detail"));
        po.setStatus(rs.getString("status"));
        po.setCreatedAt(RowMappers.instant(rs, "created_at"));
        return po;
    }

    private final JdbcTemplate jdbcTemplate;

    public ManualReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(ManualReviewPo po) {
        jdbcTemplate.update("""
                        INSERT INTO manual_review_queue
                            (request_id, flight_number, reason, detail, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                po.getRequestId(), po.getFlightNumber(), po.getReason(), po.getDetail(),
                po.getStatus(), Timestamp.from(po.getCreatedAt()));
    }

    public List<ManualReviewPo> findAll() {
        return jdbcTemplate.query("""
                        SELECT id, request_id, flight_number, reason, detail, status, created_at
                        FROM manual_review_queue ORDER BY id
                        """,
                MAPPER);
    }

    public ManualReviewPo findByRequestId(String requestId) {
        List<ManualReviewPo> list = jdbcTemplate.query("""
                        SELECT id, request_id, flight_number, reason, detail, status, created_at
                        FROM manual_review_queue WHERE request_id = ?
                        """,
                MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }
}
