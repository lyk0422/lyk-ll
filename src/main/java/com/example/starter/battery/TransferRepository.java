package com.example.starter.battery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 站间调拨单数据访问。
 */
@Repository
public class TransferRepository {

    private static final RowMapper<Transfer> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Transfer map(ResultSet rs) throws SQLException {
        return new Transfer(
                rs.getLong("id"),
                rs.getString("battery_code"),
                rs.getString("from_station"),
                rs.getString("to_station"),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getLong("initiated_at"),
                rs.getObject("signed_at", Long.class),
                rs.getObject("exception_at", Long.class),
                rs.getObject("written_off_at", Long.class));
    }

    /** 新建调拨单，返回生成单号。 */
    public long insert(String batteryCode, String fromStation, String toStation, long now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO transfer (battery_code, from_station, to_station, status, initiated_at) "
                            + "VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batteryCode);
            ps.setString(2, fromStation);
            ps.setString(3, toStation);
            ps.setString(4, TransferStatus.IN_TRANSIT.name());
            ps.setLong(5, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    public Optional<Transfer> findById(long id) {
        return jdbc.query("SELECT * FROM transfer WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 查询电池当前在途调拨单。 */
    public Optional<Transfer> findInTransitByBattery(String batteryCode) {
        return jdbc.query(
                        "SELECT * FROM transfer WHERE battery_code = ? AND status = ?",
                        MAPPER, batteryCode, TransferStatus.IN_TRANSIT.name())
                .stream().findFirst();
    }

    /** 查询全部在途调拨单（用于超时扫描）。 */
    public List<Transfer> findAllInTransit() {
        return jdbc.query("SELECT * FROM transfer WHERE status = ?",
                MAPPER, TransferStatus.IN_TRANSIT.name());
    }

    /** 签收：仅在途单可签收，条件更新保证唯一成功。 */
    public int signIfInTransit(long id, long now) {
        return jdbc.update(
                "UPDATE transfer SET status = ?, signed_at = ? WHERE id = ? AND status = ?",
                TransferStatus.SIGNED.name(), now, id, TransferStatus.IN_TRANSIT.name());
    }

    /** 超时转异常：仅在途单可转。 */
    public int markExceptionIfInTransit(long id, long now) {
        return jdbc.update(
                "UPDATE transfer SET status = ?, exception_at = ? WHERE id = ? AND status = ?",
                TransferStatus.EXCEPTION.name(), now, id, TransferStatus.IN_TRANSIT.name());
    }

    /** 人工核销：仅异常单可核销且仅一次。 */
    public int writeOffIfException(long id, long now) {
        return jdbc.update(
                "UPDATE transfer SET status = ?, written_off_at = ? WHERE id = ? AND status = ?",
                TransferStatus.WRITTEN_OFF.name(), now, id, TransferStatus.EXCEPTION.name());
    }
}
