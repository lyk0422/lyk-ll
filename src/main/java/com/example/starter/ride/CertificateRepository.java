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
 * 年度证书仓储：历史证书不可覆盖，换证通过唯一当前指针原子切换；
 * 撤销为证书实体的版本化修改（expectedVersion 乐观锁，成功变更加1）。
 */
@Repository
public class CertificateRepository {

    private static final RowMapper<Certificate> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Certificate map(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new Certificate(
                rs.getLong("id"),
                rs.getLong("ride_id"),
                rs.getString("request_id"),
                rs.getTimestamp("passed_at").toInstant(),
                rs.getTimestamp("valid_until").toInstant(),
                rs.getBoolean("revoked"),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getString("revoke_reason"),
                rs.getLong("version"));
    }

    /** 签发新证书（版本从1开始），返回证书ID；历史证书行不被覆盖。 */
    public long insert(long rideId, String requestId, Instant passedAt, Instant validUntil) {
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
        return keyHolder.getKey().longValue();
    }

    public Optional<Certificate> findById(long id) {
        return jdbc.query("SELECT * FROM annual_certificate WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 当前证书：经唯一指针表定位，撤销后指针已删除则查不到。 */
    public Optional<Certificate> findCurrent(long rideId) {
        return jdbc.query(
                        "SELECT c.* FROM annual_certificate c"
                                + " JOIN current_certificate p ON p.certificate_id = c.id"
                                + " WHERE p.ride_id = ?",
                        MAPPER, rideId)
                .stream().findFirst();
    }

    /** 设备全部证书（含历史与已撤销），按签发顺序排列，可复算。 */
    public List<Certificate> listByRide(long rideId) {
        return jdbc.query("SELECT * FROM annual_certificate WHERE ride_id = ? ORDER BY id",
                MAPPER, rideId);
    }

    /** 换证：原子切换唯一当前指针（调用方须持有设备行锁保证串行）。 */
    public void switchCurrent(long rideId, long certificateId) {
        int rows = jdbc.update(
                "UPDATE current_certificate SET certificate_id = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE ride_id = ?",
                certificateId, rideId);
        if (rows == 0) {
            jdbc.update("INSERT INTO current_certificate (ride_id, certificate_id) VALUES (?, ?)",
                    rideId, certificateId);
        }
    }

    /**
     * 撤销证书：仅当 expectedVersion 匹配时写入，版本加1并记录撤销时刻与原因；
     * 返回是否成功，失败即版本冲突。
     */
    public boolean revoke(long certificateId, long expectedVersion, String reason, Instant now) {
        int rows = jdbc.update(
                "UPDATE annual_certificate SET revoked = TRUE, revoked_at = ?,"
                        + " revoke_reason = ?, version = version + 1"
                        + " WHERE id = ? AND version = ? AND revoked = FALSE",
                Timestamp.from(now), reason, certificateId, expectedVersion);
        return rows == 1;
    }

    /** 撤销后删除当前指针：设备不再持有当前证书，历史证书仍可查。 */
    public void clearCurrent(long rideId) {
        jdbc.update("DELETE FROM current_certificate WHERE ride_id = ?", rideId);
    }
}
