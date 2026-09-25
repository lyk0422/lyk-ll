package com.example.starter.bonded;

import com.example.starter.bonded.support.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/**
 * 集成测试基类：每个用例前清空业务表并重建合成数据，避免顺序依赖；时钟重置到固定起点。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class BaseIntegrationTest {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TimeProvider timeProvider;

    protected TestClockConfig.MutableTimeProvider clock;

    @BeforeEach
    void resetState() {
        clock = (TestClockConfig.MutableTimeProvider) timeProvider;
        clock.set(T0);
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM status_history");
        jdbc.update("DELETE FROM transfer_item");
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM inventory");
        jdbc.update("DELETE FROM warehouse");
        jdbc.update("DELETE FROM enterprise");
        jdbc.update("INSERT INTO enterprise (id, name, frozen) VALUES ('ENT-001','合成外贸企业甲',0)");
        jdbc.update("INSERT INTO enterprise (id, name, frozen) VALUES ('ENT-002','合成外贸企业乙',0)");
        jdbc.update("INSERT INTO warehouse (id, name) VALUES ('WH-A','合成保税区 A 仓')");
        jdbc.update("INSERT INTO warehouse (id, name) VALUES ('WH-B','合成保税区 B 仓')");
        jdbc.update("INSERT INTO inventory (warehouse_id, hs_code, total_qty, frozen_qty) VALUES ('WH-A','HS-1001',1000,0)");
        jdbc.update("INSERT INTO inventory (warehouse_id, hs_code, total_qty, frozen_qty) VALUES ('WH-A','HS-1002',500,0)");
        jdbc.update("INSERT INTO inventory (warehouse_id, hs_code, total_qty, frozen_qty) VALUES ('WH-B','HS-1001',200,0)");
    }

    /**
     * 查询库存快照：[total_qty, frozen_qty]。
     */
    protected long[] inventoryOf(String warehouseId, String hsCode) {
        return jdbc.query(
                "SELECT total_qty, frozen_qty FROM inventory WHERE warehouse_id = ? AND hs_code = ?",
                rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : null,
                warehouseId, hsCode);
    }

    /**
     * 企业冻结标记。
     */
    protected boolean enterpriseFrozen(String enterpriseId) {
        Integer v = jdbc.queryForObject("SELECT frozen FROM enterprise WHERE id = ?", Integer.class, enterpriseId);
        return v != null && v == 1;
    }

    /**
     * 单据当前状态。
     */
    protected String statusOf(String requestId) {
        return jdbc.queryForObject("SELECT status FROM transfer_order WHERE request_id = ?", String.class, requestId);
    }
}
