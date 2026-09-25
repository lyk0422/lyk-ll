package com.example.starter.domain;

import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 基站主数据。
 *
 * @param id                  主键
 * @param stationCode         基站编号（业务唯一键）
 * @param stationName         基站名称
 * @param capacityWh          电池组额定容量，单位 Wh
 * @param dischargeRateW      电池组平均放电功率，单位 W
 * @param currentRemainingWh  最近已知剩余容量，单位 Wh；NULL 表示尚无抄表，回充后重置为额定容量
 * @param createdAt           创建时刻，UTC
 */
public record BaseStation(
        Long id,
        String stationCode,
        String stationName,
        BigDecimal capacityWh,
        BigDecimal dischargeRateW,
        BigDecimal currentRemainingWh,
        Instant createdAt
) {

    public static RowMapper<BaseStation> rowMapper() {
        return (ResultSet rs, int rowNum) -> new BaseStation(
                rs.getLong("id"),
                rs.getString("station_code"),
                rs.getString("station_name"),
                rs.getBigDecimal("battery_capacity_wh"),
                rs.getBigDecimal("discharge_rate_w"),
                rs.getBigDecimal("current_remaining_wh"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    /** 将 UTC 时刻绑定为 SQL 时间戳。 */
    public static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
