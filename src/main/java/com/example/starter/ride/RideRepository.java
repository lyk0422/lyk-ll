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
        boolean checkDateNull = rs.wasNull();
        Date reopenDate = rs.getDate("reopen_date");
        boolean reopenDateNull = rs.wasNull();
        long currentCert = rs.getLong("current_certificate_id");
        Long currentCertificateId = rs.wasNull() ? null : currentCert;
        return new Ride(
                rs.getLong("id"),
                rs.getString("name"),
                RideStatus.valueOf(rs.getString("status")),
                rs.getInt("max_load"),
                rs.getInt("max_speed"),
                rs.getInt("trial_count"),
                rs.getInt("reopen_count"),
                checkDateNull ? null : checkDate.toLocalDate(),
                reopenDateNull ? null : reopenDate.toLocalDate(),
                rs.getBoolean("inspection_required"),
                rs.getInt("inspection_cycle_version"),
                rs.getInt("overhaul_version"),
                currentCertificateId,
                rs.getLong("version"));
    }

    /**
     * 插入新设备，初始状态为待日检。
     * inspectionRequired 为合成受检标志；cycleVersion 为合成检验周期版本，缺省1。
     */
    public Ride insert(String name, int maxLoad, int maxSpeed,
            boolean inspectionRequired, int cycleVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ride (name, status, max_load, max_speed,"
                            + " inspection_required, inspection_cycle_version)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, RideStatus.PENDING_CHECK.name());
            ps.setInt(3, maxLoad);
            ps.setInt(4, maxSpeed);
            ps.setBoolean(5, inspectionRequired);
            ps.setInt(6, cycleVersion);
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
     * 乐观锁更新（不含大修版本与当前证书指针）：仅当版本号匹配时写入，返回是否成功。
     * 并发下只有一条状态链能生效。
     */
    public boolean updateWithVersion(Ride ride, RideStatus newStatus, int trialCount,
            int reopenCount, LocalDate checkDate, LocalDate reopenDate) {
        return updateWithVersion(ride, newStatus, trialCount, reopenCount,
                checkDate, reopenDate, ride.overhaulVersion(), ride.currentCertificateId());
    }

    /**
     * 乐观锁完整更新：状态、计数、日期、大修版本与当前年度证书唯一指针原子切换；
     * version 从1开始，每次成功变更 version = version + 1。
     */
    public boolean updateWithVersion(Ride ride, RideStatus newStatus, int trialCount,
            int reopenCount, LocalDate checkDate, LocalDate reopenDate,
            Integer overhaulVersion, Long currentCertificateId) {
        int rows = jdbc.update(
                "UPDATE ride SET status = ?, trial_count = ?, reopen_count = ?,"
                        + " check_date = ?, reopen_date = ?, overhaul_version = ?,"
                        + " current_certificate_id = ?, version = version + 1"
                        + " WHERE id = ? AND version = ?",
                newStatus.name(), trialCount, reopenCount,
                checkDate == null ? null : Date.valueOf(checkDate),
                reopenDate == null ? null : Date.valueOf(reopenDate),
                overhaulVersion, currentCertificateId,
                ride.id(), ride.version());
        return rows == 1;
    }
}
