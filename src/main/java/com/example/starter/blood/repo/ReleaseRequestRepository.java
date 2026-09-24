package com.example.starter.blood.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.starter.blood.domain.ReleaseRequestLog;
import com.example.starter.blood.domain.UnitStatus;

/**
 * 放行请求幂等台账数据访问：request_id 主键保证同键只落库一次。
 */
@Repository
public class ReleaseRequestRepository {

    private final JdbcClient jdbc;

    public ReleaseRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询首次处理结果。
     *
     * @param requestId 幂等键
     * @return 台账记录
     */
    public Optional<ReleaseRequestLog> findByRequestId(String requestId) {
        return jdbc.sql("SELECT * FROM release_request WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 落库首次处理结果；并发同键时由主键约束保证唯一，冲突由调用方捕获后重读。
     *
     * @param log 台账记录
     */
    public void insert(ReleaseRequestLog log) {
        jdbc.sql("INSERT INTO release_request (request_id, fingerprint, barcode, outcome, unit_status, message, created_at)"
                        + " VALUES (:requestId, :fingerprint, :barcode, :outcome, :unitStatus, :message, :createdAt)")
                .param("requestId", log.requestId())
                .param("fingerprint", log.fingerprint())
                .param("barcode", log.barcode())
                .param("outcome", log.outcome())
                .param("unitStatus", log.unitStatus().name())
                .param("message", log.message())
                .param("createdAt", Timestamp.from(log.createdAt()))
                .update();
    }

    private ReleaseRequestLog mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReleaseRequestLog(
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("barcode"),
                rs.getString("outcome"),
                UnitStatus.valueOf(rs.getString("unit_status")),
                rs.getString("message"),
                rs.getTimestamp("created_at").toInstant());
    }
}
