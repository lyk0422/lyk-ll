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

import com.example.starter.ride.AnnualCertificate.CertStatus;

/**
 * 年度证书仓储（合成规则）：证书只增不改事实字段，状态仅沿 ACTIVE→SUPERSEDED/REVOKED 流转。
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
                rs.getString("certificate_no"),
                rs.getInt("cycle_version"),
                rs.getInt("overhaul_version"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                CertStatus.valueOf(rs.getString("status")),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getString("request_id"));
    }

    /** 插入一张新证书，返回带主键的实体；编号同设备唯一由约束 uq_annual_certificate_no 兜底。 */
    public AnnualCertificate insert(long rideId, String certificateNo, int cycleVersion,
            int overhaulVersion, Instant issuedAt, Instant expiresAt, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO annual_certificate (ride_id, certificate_no, cycle_version,"
                            + " overhaul_version, issued_at, expires_at, status, revoked_at,"
                            + " request_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)",
                    new String[]{"id"});
            ps.setLong(1, rideId);
            ps.setString(2, certificateNo);
            ps.setInt(3, cycleVersion);
            ps.setInt(4, overhaulVersion);
            ps.setTimestamp(5, Timestamp.from(issuedAt));
            ps.setTimestamp(6, Timestamp.from(expiresAt));
            ps.setString(7, CertStatus.ACTIVE.name());
            ps.setString(8, requestId);
            return ps;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public Optional<AnnualCertificate> findById(long id) {
        return jdbc.query("SELECT * FROM annual_certificate WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 列出设备全部证书（含历史与已撤销），按发证先后排列，供证据查询。 */
    public List<AnnualCertificate> listByRide(long rideId) {
        return jdbc.query(
                "SELECT * FROM annual_certificate WHERE ride_id = ? ORDER BY id", MAPPER, rideId);
    }

    /** 换证：旧当前证书原子置为历史，不覆盖其事实字段。 */
    public int markSuperseded(long certificateId) {
        return jdbc.update(
                "UPDATE annual_certificate SET status = ? WHERE id = ? AND status = ?",
                CertStatus.SUPERSEDED.name(), certificateId, CertStatus.ACTIVE.name());
    }

    /** 撤销：仅 ACTIVE 证书可撤销，记录撤销时刻，返回受影响行数。 */
    public int markRevoked(long certificateId, Instant revokedAt) {
        return jdbc.update(
                "UPDATE annual_certificate SET status = ?, revoked_at = ?"
                        + " WHERE id = ? AND status = ?",
                CertStatus.REVOKED.name(), Timestamp.from(revokedAt),
                certificateId, CertStatus.ACTIVE.name());
    }
}
