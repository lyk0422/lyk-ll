package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/**
 * 验收清场凭据数据访问：acceptance_credential 表。
 */
@Repository
public class AcceptanceCredentialRepository {

    private static final RowMapper<AcceptanceCredential> MAPPER = (rs, rowNum) -> new AcceptanceCredential(
            rs.getLong("id"),
            rs.getLong("segment_id"),
            rs.getLong("order_id"),
            rs.getString("inspector_id"),
            rs.getString("credential"),
            rs.getTimestamp("submitted_at").toInstant(),
            rs.getBoolean("late"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public AcceptanceCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入验收凭据。 */
    public AcceptanceCredential insert(AcceptanceCredential credential) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO acceptance_credential (segment_id, order_id, inspector_id, credential,"
                            + " submitted_at, late, request_id, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, credential.segmentId());
            ps.setLong(2, credential.orderId());
            ps.setString(3, credential.inspectorId());
            ps.setString(4, credential.credential());
            ps.setTimestamp(5, Timestamp.from(credential.submittedAt()));
            ps.setBoolean(6, credential.late());
            ps.setString(7, credential.requestId());
            ps.setTimestamp(8, Timestamp.from(credential.createdAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return jdbc.query("SELECT * FROM acceptance_credential WHERE id = ?", MAPPER, id)
                .stream().findFirst().orElseThrow();
    }

    /** 按施工段列出全部凭据（含迟到登记），按主键排序。 */
    public List<AcceptanceCredential> findBySegmentId(long segmentId) {
        return jdbc.query("SELECT * FROM acceptance_credential WHERE segment_id = ? ORDER BY id",
                MAPPER, segmentId);
    }

    /** 按施工单列出全部凭据（含迟到登记），按主键排序。 */
    public List<AcceptanceCredential> findByOrderId(long orderId) {
        return jdbc.query("SELECT * FROM acceptance_credential WHERE order_id = ? ORDER BY id",
                MAPPER, orderId);
    }
}
