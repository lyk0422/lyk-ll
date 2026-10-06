package com.example.starter.waste;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 回执与异常核查单数据访问。
 */
@Repository
public class ReceiptRepository {

    private final JdbcClient jdbc;

    public ReceiptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询回执。
     */
    public Optional<Receipt> findReceiptByRequestId(String requestId) {
        return jdbc.sql("""
                        SELECT id, manifest_id, request_id, fingerprint, received_net_kg, deviation_ratio, received_at
                        FROM receipt WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(this::mapReceipt)
                .optional();
    }

    /**
     * 写入回执。
     */
    public long insertReceipt(long manifestId, String requestId, String fingerprint,
            java.math.BigDecimal receivedNetKg, java.math.BigDecimal deviationRatio, LocalDateTime receivedAt) {
        jdbc.sql("""
                INSERT INTO receipt (manifest_id, request_id, fingerprint, received_net_kg, deviation_ratio, received_at)
                VALUES (:manifestId, :requestId, :fingerprint, :receivedNetKg, :deviationRatio, :receivedAt)
                """)
                .param("manifestId", manifestId)
                .param("requestId", requestId)
                .param("fingerprint", fingerprint)
                .param("receivedNetKg", receivedNetKg)
                .param("deviationRatio", deviationRatio)
                .param("receivedAt", Timestamp.valueOf(receivedAt))
                .update();
        return findReceiptByRequestId(requestId).orElseThrow().id();
    }

    /**
     * 按联单查询异常核查单。
     */
    public Optional<ExceptionCase> findExceptionByManifestId(long manifestId) {
        return jdbc.sql(exceptionSelect() + " WHERE manifest_id = :manifestId")
                .param("manifestId", manifestId)
                .query(this::mapException)
                .optional();
    }

    /**
     * 按主键查询异常核查单。
     */
    public Optional<ExceptionCase> findExceptionById(long id) {
        return jdbc.sql(exceptionSelect() + " WHERE id = :id")
                .param("id", id)
                .query(this::mapException)
                .optional();
    }

    /**
     * 按结论幂等键查询异常核查单。
     */
    public Optional<ExceptionCase> findExceptionByConclusionRequestId(String requestId) {
        return jdbc.sql(exceptionSelect() + " WHERE conclusion_request_id = :requestId")
                .param("requestId", requestId)
                .query(this::mapException)
                .optional();
    }

    /**
     * 生成异常核查单并返回主键。
     */
    public long insertException(long manifestId, LocalDateTime createdAt) {
        jdbc.sql("INSERT INTO exception_case (manifest_id, status, created_at) VALUES (:manifestId, 'OPEN', :createdAt)")
                .param("manifestId", manifestId)
                .param("createdAt", Timestamp.valueOf(createdAt))
                .update();
        return findExceptionByManifestId(manifestId).orElseThrow().id();
    }

    /**
     * 写入核查结论留痕并置为已结论。
     */
    public void resolveException(long id, ConclusionType type, String note,
            String conclusionRequestId, String conclusionFingerprint, LocalDateTime resolvedAt) {
        jdbc.sql("""
                UPDATE exception_case
                SET status = 'RESOLVED', conclusion_type = :type, conclusion_note = :note,
                    conclusion_request_id = :requestId, conclusion_fingerprint = :fingerprint,
                    resolved_at = :resolvedAt
                WHERE id = :id
                """)
                .param("type", type.name())
                .param("note", note)
                .param("requestId", conclusionRequestId)
                .param("fingerprint", conclusionFingerprint)
                .param("resolvedAt", Timestamp.valueOf(resolvedAt))
                .param("id", id)
                .update();
    }

    private String exceptionSelect() {
        return """
                SELECT id, manifest_id, status, conclusion_type, conclusion_note,
                       conclusion_request_id, conclusion_fingerprint, created_at, resolved_at
                FROM exception_case
                """;
    }

    private Receipt mapReceipt(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Receipt(
                rs.getLong("id"),
                rs.getLong("manifest_id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getBigDecimal("received_net_kg"),
                rs.getBigDecimal("deviation_ratio"),
                rs.getTimestamp("received_at").toLocalDateTime());
    }

    private ExceptionCase mapException(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new ExceptionCase(
                rs.getLong("id"),
                rs.getLong("manifest_id"),
                rs.getString("status"),
                rs.getString("conclusion_type"),
                rs.getString("conclusion_note"),
                rs.getString("conclusion_request_id"),
                rs.getString("conclusion_fingerprint"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                resolvedAt == null ? null : resolvedAt.toLocalDateTime());
    }
}
