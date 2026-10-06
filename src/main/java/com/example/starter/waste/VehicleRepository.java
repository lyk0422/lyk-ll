package com.example.starter.waste;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 车辆与皮重记录数据访问。
 */
@Repository
public class VehicleRepository {

    private final JdbcClient jdbc;

    public VehicleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按车牌查询车辆主键。
     */
    public Optional<Long> findVehicleIdByPlate(String plateNo) {
        return jdbc.sql("SELECT id FROM vehicle WHERE plate_no = :plateNo")
                .param("plateNo", plateNo)
                .query(Long.class)
                .optional();
    }

    /**
     * 登记新车辆并返回主键。
     */
    public long insertVehicle(String plateNo, LocalDateTime createdAt) {
        jdbc.sql("INSERT INTO vehicle (plate_no, created_at) VALUES (:plateNo, :createdAt)")
                .param("plateNo", plateNo)
                .param("createdAt", Timestamp.valueOf(createdAt))
                .update();
        return jdbc.sql("SELECT id FROM vehicle WHERE plate_no = :plateNo")
                .param("plateNo", plateNo)
                .query(Long.class)
                .single();
    }

    /**
     * 按主键查询车牌号。
     */
    public String findPlateById(long vehicleId) {
        return jdbc.sql("SELECT plate_no FROM vehicle WHERE id = :id")
                .param("id", vehicleId)
                .query(String.class)
                .single();
    }

    /**
     * 最近一次空车皮重记录，无记录时为空。
     */
    public Optional<TareRecord> findLatestTare(long vehicleId) {
        return jdbc.sql("""
                        SELECT id, tare_kg, weighed_at FROM tare_record
                        WHERE vehicle_id = :vehicleId
                        ORDER BY weighed_at DESC, id DESC
                        LIMIT 1
                        """)
                .param("vehicleId", vehicleId)
                .query((rs, rowNum) -> new TareRecord(
                        rs.getLong("id"),
                        rs.getBigDecimal("tare_kg"),
                        rs.getTimestamp("weighed_at").toLocalDateTime()))
                .optional();
    }

    /**
     * 写入皮重复称记录。
     */
    public void insertTare(long vehicleId, java.math.BigDecimal tareKg, LocalDateTime weighedAt) {
        jdbc.sql("INSERT INTO tare_record (vehicle_id, tare_kg, weighed_at) VALUES (:vehicleId, :tareKg, :weighedAt)")
                .param("vehicleId", vehicleId)
                .param("tareKg", tareKg)
                .param("weighedAt", Timestamp.valueOf(weighedAt))
                .update();
    }

    /**
     * 皮重记录行。
     */
    public record TareRecord(long id, java.math.BigDecimal tareKg, LocalDateTime weighedAt) {
    }
}
