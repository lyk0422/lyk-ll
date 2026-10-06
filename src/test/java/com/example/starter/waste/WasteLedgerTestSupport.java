package com.example.starter.waste;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 台账测试基座：独立 H2 内存库，每个用例前清空业务表（保留设施目录），避免顺序依赖。
 */
@SpringBootTest
public abstract class WasteLedgerTestSupport {

    /** 目录内有效设施编码，来自 data.sql 合成数据。 */
    protected static final String FACILITY_KITCHEN = "F-KITCHEN";
    protected static final String FACILITY_INCINERATION = "F-INCINERATION";

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessTables() {
        jdbc.update("DELETE FROM exception_case");
        jdbc.update("DELETE FROM receipt");
        jdbc.update("DELETE FROM manifest");
        jdbc.update("DELETE FROM tare_record");
        jdbc.update("DELETE FROM vehicle");
    }

    /**
     * 造一辆带指定皮重与称重时间的车辆，返回车辆主键。
     */
    protected long givenVehicle(String plateNo, String tareKg, LocalDateTime weighedAt) {
        jdbc.update("INSERT INTO vehicle (plate_no, created_at) VALUES (?, ?)", plateNo, Timestamp.valueOf(weighedAt));
        Long vehicleId = jdbc.queryForObject("SELECT id FROM vehicle WHERE plate_no = ?", Long.class, plateNo);
        jdbc.update("INSERT INTO tare_record (vehicle_id, tare_kg, weighed_at) VALUES (?, ?, ?)",
                vehicleId, new BigDecimal(tareKg), Timestamp.valueOf(weighedAt));
        return vehicleId;
    }

    /**
     * 构造进场登记请求，tareKg 为 null 表示不携带复称皮重。
     */
    protected EntryWeighingRequest entryRequest(String requestId, String plateNo, String category,
            String facilityCode, String grossKg, String tareKg) {
        return new EntryWeighingRequest(requestId, plateNo, "阳光小区", category, facilityCode,
                new BigDecimal(grossKg), tareKg == null ? null : new BigDecimal(tareKg));
    }

    /**
     * 唯一请求键。
     */
    protected String newRequestId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * 唯一车牌。
     */
    protected String newPlate() {
        return "沪T" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    protected long countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
