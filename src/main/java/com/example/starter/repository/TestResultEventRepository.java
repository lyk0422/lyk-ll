package com.example.starter.repository;

import com.example.starter.domain.TestResultCode;
import com.example.starter.domain.TestResultEvent;
import com.example.starter.domain.TestType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * 检测结果事件仓储。终态后的回传仍落库为迟到事件（accepted=FALSE）。
 */
@Repository
public class TestResultEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TestResultEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TestResultEvent> MAPPER = (rs, n) -> {
        TestResultEvent e = new TestResultEvent();
        e.setId(rs.getLong("id"));
        e.setBarcode(rs.getString("barcode"));
        e.setTestType(TestType.valueOf(rs.getString("test_type")));
        e.setResult(TestResultCode.valueOf(rs.getString("result")));
        e.setAccepted(rs.getBoolean("accepted"));
        e.setReceivedAt(rs.getTimestamp("received_at").toInstant());
        e.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return e;
    };

    public void insert(TestResultEvent event) {
        String sql = "INSERT INTO test_result_event (barcode, test_type, result, accepted, received_at, created_at) "
                + "VALUES (:barcode, :testType, :result, :accepted, :receivedAt, :createdAt)";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("barcode", event.getBarcode())
                .addValue("testType", event.getTestType().name())
                .addValue("result", event.getResult().name())
                .addValue("accepted", event.isAccepted())
                .addValue("receivedAt", BloodUnitRepository.ts(event.getReceivedAt()))
                .addValue("createdAt", BloodUnitRepository.ts(event.getCreatedAt()));
        jdbc.update(sql, p);
    }

    public long countByBarcodeAndAccepted(String barcode, boolean accepted) {
        String sql = "SELECT COUNT(*) FROM test_result_event WHERE barcode = :barcode AND accepted = :accepted";
        Long count = jdbc.queryForObject(sql,
                new MapSqlParameterSource("barcode", barcode).addValue("accepted", accepted),
                Long.class);
        return count == null ? 0 : count;
    }

}
