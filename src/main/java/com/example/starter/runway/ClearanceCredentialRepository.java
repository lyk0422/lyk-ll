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
import java.util.Optional;

/**
 * 清场凭据数据访问：clearance_credential 表。
 */
@Repository
public class ClearanceCredentialRepository {

    private static final RowMapper<ClearanceCredential> MAPPER = (rs, rowNum) -> new ClearanceCredential(
            rs.getLong("id"),
            rs.getLong("segment_id"),
            rs.getLong("order_id"),
            rs.getString("acceptor"),
            rs.getString("credential_ref"),
            rs.getTimestamp("submitted_at").toInstant(),
            rs.getInt("late") != 0,
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ClearanceCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记清场凭据（正常验收或迟到留痕）。 */
    public ClearanceCredential insert(ClearanceCredential credential) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO clearance_credential (segment_id, order_id, acceptor, credential_ref,"
                            + " submitted_at, late, request_id, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, credential.segmentId());
            ps.setLong(2, credential.orderId());
            ps.setString(3, credential.acceptor());
            ps.setString(4, credential.credentialRef());
            ps.setTimestamp(5, Timestamp.from(credential.submittedAt()));
            ps.setInt(6, credential.late() ? 1 : 0);
            ps.setString(7, credential.requestId());
            ps.setTimestamp(8, Timestamp.from(credential.createdAt()));
            return ps;
        }, keyHolder);
        long id = Optional.ofNullable(keyHolder.getKey()).orElseThrow().longValue();
        return findById(id).orElseThrow();
    }

    public Optional<ClearanceCredential> findById(long id) {
        List<ClearanceCredential> rows = jdbc.query("SELECT * FROM clearance_credential WHERE id = ?",
                MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 该段全部凭据（含迟到留痕），按主键排序。 */
    public List<ClearanceCredential> findBySegmentId(long segmentId) {
        return jdbc.query("SELECT * FROM clearance_credential WHERE segment_id = ? ORDER BY id",
                MAPPER, segmentId);
    }

    /** 该段正常验收凭据的不同验收人数（迟到留痕不计入）。 */
    public int countDistinctAcceptors(long segmentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT acceptor) FROM clearance_credential WHERE segment_id = ? AND late = 0",
                Integer.class, segmentId);
        return count == null ? 0 : count;
    }
}
