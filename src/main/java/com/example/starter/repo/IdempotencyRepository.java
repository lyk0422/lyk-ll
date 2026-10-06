package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.IdemRecord;

/**
 * 幂等记录数据访问。request_id 唯一约束是幂等键的数据库兜底。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdemRecord> MAPPER = (rs, n) -> map(rs);

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static IdemRecord map(ResultSet rs) throws SQLException {
        Integer status = rs.getObject("response_status") == null ? null : rs.getInt("response_status");
        return new IdemRecord(rs.getLong("id"), rs.getString("request_id"),
                rs.getString("fingerprint"), rs.getString("endpoint"), status,
                rs.getString("response_body"), rs.getObject("created_at", LocalDateTime.class));
    }

    public long insert(String requestId, String fingerprint, String endpoint,
                       int responseStatus, String responseBody, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO idempotency_record(request_id, fingerprint, endpoint,"
                            + " response_status, response_body, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, endpoint);
            ps.setInt(4, responseStatus);
            ps.setString(5, responseBody);
            ps.setObject(6, createdAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<IdemRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Long.class);
    }
}
