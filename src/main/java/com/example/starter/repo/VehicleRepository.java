package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 车辆档案与皮重记录的数据访问。
 */
@Repository
public class VehicleRepository {

    private final JdbcClient jdbc;

    public VehicleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 车辆档案行。
     *
     * @param id       车辆 id
     * @param plateNo  车牌号
     */
    public record VehicleRow(long id, String plateNo) {
    }

    /**
     * 皮重记录行。
     *
     * @param tareKg    空车皮重（kg）
     * @param weighedAt 空车称重时间（UTC）
     */
    public record TareRow(BigDecimal tareKg, Instant weighedAt) {
    }

    public Optional<VehicleRow> findByPlateNo(String plateNo) {
        return jdbc.sql("SELECT id, plate_no FROM vehicle WHERE plate_no = :plateNo")
                .param("plateNo", plateNo)
                .query((rs, rowNum) -> new VehicleRow(rs.getLong("id"), rs.getString("plate_no")))
                .optional();
    }

    /**
     * 查询车辆最近一次空车称重皮重。
     */
    public Optional<TareRow> findLatestTare(long vehicleId) {
        return jdbc.sql("""
                        SELECT tare_kg, weighed_at FROM tare_record
                        WHERE vehicle_id = :vehicleId
                        ORDER BY weighed_at DESC, id DESC
                        LIMIT 1
                        """)
                .param("vehicleId", vehicleId)
                .query((rs, rowNum) -> new TareRow(
                        rs.getBigDecimal("tare_kg"),
                        rs.getTimestamp("weighed_at").toInstant()))
                .optional();
    }

    /**
     * 登记一次空车皮重（复称）。
     */
    public void insertTare(long vehicleId, BigDecimal tareKg, Instant weighedAt) {
        jdbc.sql("INSERT INTO tare_record (vehicle_id, tare_kg, weighed_at) VALUES (:vehicleId, :tareKg, :weighedAt)")
                .param("vehicleId", vehicleId)
                .param("tareKg", tareKg)
                .param("weighedAt", Timestamp.from(weighedAt))
                .update();
    }
}
