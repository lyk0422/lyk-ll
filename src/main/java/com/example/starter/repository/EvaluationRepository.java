package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.domain.Evaluation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 效果评估数据访问。
 */
@Repository
public class EvaluationRepository {

    private final JdbcTemplate jdbc;

    public EvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入评估（封档）并返回生成主键。
     */
    public long insert(String requestId, String fingerprint, long permitId,
            String conclusion, Instant sealedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO evaluation (request_id, fingerprint, permit_id, conclusion, sealed_at)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, permitId);
            ps.setString(4, conclusion);
            ps.setTimestamp(5, Timestamp.from(sealedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等键查询。
     */
    public Optional<Evaluation> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM evaluation WHERE request_id = ?", this::map, requestId)
                .stream().findFirst();
    }

    /**
     * 按申请单查询评估。
     */
    public Optional<Evaluation> findByPermitId(long permitId) {
        return jdbc.query("SELECT * FROM evaluation WHERE permit_id = ?", this::map, permitId)
                .stream().findFirst();
    }

    private Evaluation map(ResultSet rs, int rowNum) throws SQLException {
        return new Evaluation(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("permit_id"),
                rs.getString("conclusion"),
                rs.getTimestamp("sealed_at").toInstant());
    }
}
