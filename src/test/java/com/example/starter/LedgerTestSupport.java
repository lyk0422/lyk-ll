package com.example.starter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.example.starter.service.WeighingService;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;

/**
 * 台账测试基类：真实 H2（MySQL 兼容模式）+ 可控时钟；每个用例前清空业务表，避免顺序依赖。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class LedgerTestSupport {

    private static final AtomicInteger PLATE_SEQUENCE = new AtomicInteger(1000);

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanLedgerTables() {
        jdbc.sql("DELETE FROM exception_review").update();
        jdbc.sql("DELETE FROM late_receipt").update();
        jdbc.sql("DELETE FROM receipt").update();
        jdbc.sql("DELETE FROM manifest_destination").update();
        jdbc.sql("DELETE FROM manifest").update();
        jdbc.sql("DELETE FROM tare_record").update();
        jdbc.sql("DELETE FROM vehicle").update();
        resetFacilityCatalog();
        clock.setInstant(TestClockConfig.BASE_INSTANT);
    }

    /**
     * 将设施目录重置为种子状态：清除停收/改版留痕/品类配置后重建，避免用例间串扰。
     */
    private void resetFacilityCatalog() {
        jdbc.sql("DELETE FROM facility_revision").update();
        jdbc.sql("DELETE FROM facility_suspension").update();
        jdbc.sql("DELETE FROM facility_category").update();
        jdbc.sql("DELETE FROM facility").update();
        jdbc.sql("""
                INSERT INTO facility (code, name, active, version) VALUES
                    ('F-INCINERATION', '区生活垃圾焚烧发电厂', 1, 1),
                    ('F-INCINERATION-2', '区第二生活垃圾焚烧发电厂', 1, 1),
                    ('F-KITCHEN', '区厨余垃圾资源化处理站', 1, 1),
                    ('F-RECYCLING', '区可回收物分拣中心', 1, 1),
                    ('F-HAZARDOUS', '区有害垃圾暂存转运点', 0, 1)
                """).update();
        jdbc.sql("""
                INSERT INTO facility_category (facility_id, category)
                SELECT f.id, c.category FROM facility f
                JOIN (
                    SELECT 'F-INCINERATION' AS code, 'OTHER' AS category UNION ALL
                    SELECT 'F-INCINERATION-2', 'OTHER' UNION ALL
                    SELECT 'F-KITCHEN', 'KITCHEN' UNION ALL
                    SELECT 'F-RECYCLING', 'RECYCLABLE' UNION ALL
                    SELECT 'F-HAZARDOUS', 'HAZARDOUS'
                ) c ON c.code = f.code
                """).update();
    }

    /**
     * 登记一辆测试车辆并返回车辆 id。
     */
    protected long createVehicle() {
        String plateNo = "沪T" + PLATE_SEQUENCE.incrementAndGet();
        jdbc.sql("INSERT INTO vehicle (plate_no) VALUES (:plateNo)")
                .param("plateNo", plateNo)
                .update();
        return jdbc.sql("SELECT id FROM vehicle WHERE plate_no = :plateNo")
                .param("plateNo", plateNo)
                .query(Long.class)
                .single();
    }

    protected String plateOf(long vehicleId) {
        return jdbc.sql("SELECT plate_no FROM vehicle WHERE id = :id")
                .param("id", vehicleId)
                .query(String.class)
                .single();
    }

    /**
     * 为车辆写入一条指定时刻的空车皮重记录。
     */
    protected void insertTare(long vehicleId, BigDecimal tareKg, Instant weighedAt) {
        jdbc.sql("INSERT INTO tare_record (vehicle_id, tare_kg, weighed_at) VALUES (:vehicleId, :tareKg, :weighedAt)")
                .param("vehicleId", vehicleId)
                .param("tareKg", tareKg)
                .param("weighedAt", Timestamp.from(weighedAt))
                .update();
    }

    /**
     * 最近一次皮重记录条数（用于验证复称是否同事务落库或回滚）。
     */
    protected int tareRecordCount(long vehicleId) {
        return jdbc.sql("SELECT COUNT(*) FROM tare_record WHERE vehicle_id = :vehicleId")
                .param("vehicleId", vehicleId)
                .query(Integer.class)
                .single();
    }

    protected int manifestCount(long vehicleId) {
        return jdbc.sql("SELECT COUNT(*) FROM manifest WHERE vehicle_id = :vehicleId")
                .param("vehicleId", vehicleId)
                .query(Integer.class)
                .single();
    }

    protected String manifestStatus(long manifestId) {
        return jdbc.sql("SELECT status FROM manifest WHERE id = :id")
                .param("id", manifestId)
                .query(String.class)
                .single();
    }

    /**
     * 构造一条默认进场登记命令：小区“示例小区”，品类 KITCHEN，去向 F-KITCHEN（目录内有效设施）。
     */
    protected WeighingService.EntryCommand entryCommand(String requestId, long vehicleId,
                                                        BigDecimal grossKg, BigDecimal tareKg) {
        return new WeighingService.EntryCommand(
                requestId, plateOf(vehicleId), "示例小区", "KITCHEN", grossKg, "F-KITCHEN", tareKg);
    }
}
