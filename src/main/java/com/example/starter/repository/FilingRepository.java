package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.domain.Filing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 起爆备案数据访问。
 */
@Repository
public class FilingRepository {

    private final JdbcTemplate jdbc;

    public FilingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入备案并返回生成主键。
     */
    public long insert(String requestId, String fingerprint, long permitId, Instant filedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO blast_filing (request_id, fingerprint, permit_id, filed_at)"
                            + " VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, permitId);
            ps.setTimestamp(4, Timestamp.from(filedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等键查询。
     */
    public Optional<Filing> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM blast_filing WHERE request_id = ?", this::map, requestId)
                .stream().findFirst();
    }

    /**
     * 按申请单查询备案。
     */
    public Optional<Filing> findByPermitId(long permitId) {
        return jdbc.query("SELECT * FROM blast_filing WHERE permit_id = ?", this::map, permitId)
                .stream().findFirst();
    }

    private Filing map(ResultSet rs, int rowNum) throws SQLException {
        return new Filing(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("permit_id"),
                rs.getTimestamp("filed_at").toInstant());
    }
}
