package com.example.starter.weight.repo;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.weight.repo.po.AxleLimitRow;
import com.example.starter.weight.repo.po.StationRow;
import com.example.starter.weight.repo.po.VehicleRow;

/**
 * 合成基础数据仓储：车辆档案、轴型限值、检测站。
 */
@Repository
public class ReferenceRepository {

    private final JdbcTemplate jdbc;

    public ReferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按车牌查询车辆档案；不存在返回 null。
     */
    public VehicleRow findVehicle(String plateNo) {
        return jdbc.query("SELECT plate_no, axle_type FROM vehicle WHERE plate_no = ?",
                rs -> rs.next() ? new VehicleRow(rs.getString("plate_no"), rs.getString("axle_type")) : null,
                plateNo);
    }

    /**
     * 按检测站编码查询检测站；不存在返回 null。
     */
    public StationRow findStation(String stationCode) {
        return jdbc.query("SELECT station_code, station_name FROM station WHERE station_code = ?",
                rs -> rs.next()
                        ? new StationRow(rs.getString("station_code"), rs.getString("station_name"))
                        : null,
                stationCode);
    }

    /**
     * 按轴型查询限值（千克）；不存在返回 null。
     */
    public AxleLimitRow findAxleLimit(String axleType) {
        return jdbc.query("SELECT axle_type, limit_kg FROM axle_limit WHERE axle_type = ?",
                rs -> rs.next()
                        ? new AxleLimitRow(rs.getString("axle_type"), rs.getBigDecimal("limit_kg"))
                        : null,
                axleType);
    }

    /**
     * 按轴型查询限值数值（千克）；不存在返回 null。
     */
    public BigDecimal findLimitKg(String axleType) {
        AxleLimitRow row = findAxleLimit(axleType);
        return row == null ? null : row.limitKg();
    }

    /**
     * 按车牌查询车辆档案并加行锁（须在事务内调用），
     * 用于串行化同车牌的非现场接入，保证时刻窗口去重的并发正确性；不存在返回 null。
     */
    public VehicleRow findVehicleForUpdate(String plateNo) {
        return jdbc.query("SELECT plate_no, axle_type FROM vehicle WHERE plate_no = ? FOR UPDATE",
                rs -> rs.next() ? new VehicleRow(rs.getString("plate_no"), rs.getString("axle_type")) : null,
                plateNo);
    }
}
