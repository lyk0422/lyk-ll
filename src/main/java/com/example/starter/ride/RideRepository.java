package com.example.starter.ride;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 设备主表仓储：提供行级悲观锁与乐观锁更新，保证同一设备同时刻只有一条状态链。
 */
@Repository
public class RideRepository {

    private static final RowMapper<Ride> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public RideRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Ride map(ResultSet rs) throws SQLException {
        Date checkDate = rs.getDate("check_date");
        Date reopenDate = rs.getDate("reopen_date");
        long certificateId = rs.getLong("current_certificate_id");
        boolean certificateNull = rs.wasNull();
        return new Ride(
                rs.getLong("id"),
                rs.getString("name"),
                RideStatus.valueOf(rs.getString("status")),
                rs.getInt("max_load"),
                rs.getInt("max_speed"),
                rs.getInt("trial_count"),
                rs.getInt("reopen_count"),
                checkDate == null ? null : checkDate.toLocalDate(),
                reopenDate == null ? null : reopenDate.toLocalDate(),
                rs.getBoolean("inspection_required"),
                rs.getLong("overhaul_version"),
                certificateNull ? null : certificateId,
                rs.getLong("version"));
    }

    /** 插入新设备，初始状态为待日检；inspectionRequired 为受检标志。 */
    public Ride insert(String name, int maxLoad, int maxSpeed, boolean inspectionRequired) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ride (name, status, max_load, max_speed, inspection_required)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, RideStatus.PENDING_CHECK.name());
            ps.setInt(3, maxLoad);
            ps.setInt(4, maxSpeed);
            ps.setBoolean(5, inspectionRequired);
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return findById(id).orElseThrow();
    }

    public Optional<Ride> findById(long id) {
        return jdbc.query("SELECT * FROM ride WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 行级悲观锁读取：同事务内串行化同一设备的状态流转。 */
    public Optional<Ride> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM ride WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 乐观锁更新：仅当版本号匹配时写入，返回是否成功。
     * 并发下只有一条状态链能生效。
     */
    public boolean updateWithVersion(Ride ride, RideStatus newStatus, int trialCount,
            int reopenCount, LocalDate checkDate, LocalDate reopenDate) {
        int rows = jdbc.update(
                "UPDATE ride SET status = ?, trial_count = ?, reopen_count = ?,"
                        + " check_date = ?, reopen_date = ?, version = version + 1"
                        + " WHERE id = ? AND version = ?",
                newStatus.name(), trialCount, reopenCount,
                checkDate == null ? null : Date.valueOf(checkDate),
                reopenDate == null ? null : Date.valueOf(reopenDate),
                ride.id(), ride.version());
        return rows == 1;
    }

    /** 换证原子切换唯一当前指针：仅移动指针并递增版本，历史证书不可覆盖。 */
    public boolean updateCurrentCertificate(Ride ride, long certificateId) {
        int rows = jdbc.update(
                "UPDATE ride SET current_certificate_id = ?, version = version + 1"
                        + " WHERE id = ? AND version = ?",
                certificateId, ride.id(), ride.version());
        return rows == 1;
    }

    /** 登记大修：原子递增大修版本；若设备开放中则同时暂停开放（转为故障停运待复检）。 */
    public boolean updateOverhaul(Ride ride, long newOverhaulVersion, RideStatus newStatus) {
        int rows = jdbc.update(
                "UPDATE ride SET overhaul_version = ?, status = ?, version = version + 1"
                        + " WHERE id = ? AND version = ?",
                newOverhaulVersion, newStatus.name(), ride.id(), ride.version());
        return rows == 1;
    }

    /** 仅递增乐观锁版本：监督检验确认、证书撤销等聚合变更使用。 */
    public boolean touchVersion(Ride ride) {
        int rows = jdbc.update(
                "UPDATE ride SET version = version + 1 WHERE id = ? AND version = ?",
                ride.id(), ride.version());
        return rows == 1;
    }
}
