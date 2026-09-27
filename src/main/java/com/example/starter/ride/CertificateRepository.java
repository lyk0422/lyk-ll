package com.example.starter.ride;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 年度检验证书仓储：证书行不可变（历史证书不可覆盖），仅支持签发、撤销与查询。
 */
@Repository
public class CertificateRepository {

    private static final RowMapper<AnnualCertificate> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static AnnualCertificate map(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new AnnualCertificate(
                rs.getLong("id"),
                rs.getLong("ride_id"),
                rs.getTimestamp("passed_at").toInstant(),
                rs.getTimestamp("valid_until").toInstant(),
                rs.getBoolean("revoked"),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    /** 签发新证书（换证时历史证书保留不覆盖），返回新证书。 */
    public AnnualCertificate insert(long rideId, String requestId,
            Instant passedAt, Instant validUntil) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO annual_certificate (ride_id, request_id, passed_at, valid_until)"
                            + " VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, rideId);
            ps.setString(2, requestId);
            ps.setTimestamp(3, Timestamp.from(passedAt));
            ps.setTimestamp(4, Timestamp.from(validUntil));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return findById(id).orElseThrow();
    }

    public Optional<AnnualCertificate> findById(long id) {
        return jdbc.query("SELECT * FROM annual_certificate WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 撤销证书：仅未撤销时可撤销，返回是否成功。 */
    public boolean revoke(long certificateId, Instant revokedAt) {
        int rows = jdbc.update(
                "UPDATE annual_certificate SET revoked = TRUE, revoked_at = ?"
                        + " WHERE id = ? AND revoked = FALSE",
                Timestamp.from(revokedAt), certificateId);
        return rows == 1;
    }

    /** 设备全部证书（含历史与已撤销），按签发顺序。 */
    public List<AnnualCertificate> listByRide(long rideId) {
        return jdbc.query("SELECT * FROM annual_certificate WHERE ride_id = ? ORDER BY id",
                MAPPER, rideId);
    }
}
