package com.example.starter.transfer;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.transfer.TransferDtos.ApplyRequest;
import com.example.starter.transfer.TransferDtos.ItemRequest;

/**
 * 转关测试基类：使用独立 H2 内存库（transfer_test），每个用例前清空业务数据并重置可控时钟。
 */
@SpringBootTest
@Import(MutableClockConfig.class)
public abstract class BaseTransferTest {

    protected static final String TARIFF_A = "0901.21";
    protected static final String TARIFF_B = "8542.31";

    @Autowired
    protected TransferService service;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected Clock clock;

    protected MutableClock mutableClock;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM transfer_status_history");
        jdbc.update("DELETE FROM transfer_verification");
        jdbc.update("DELETE FROM transfer_order_item");
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM enterprise_account");
        jdbc.update("DELETE FROM warehouse_stock");
        mutableClock = (MutableClock) clock;
        mutableClock.set(MutableClockConfig.T0);
    }

    protected void seedStock(String warehouse, String tariffCode, long quantity) {
        jdbc.update("INSERT INTO warehouse_stock (warehouse_code, tariff_code, quantity) VALUES (?,?,?)",
                warehouse, tariffCode, quantity);
    }

    protected long stockOf(String warehouse, String tariffCode) {
        return jdbc.queryForObject(
                "SELECT quantity FROM warehouse_stock WHERE warehouse_code = ? AND tariff_code = ?",
                Long.class, warehouse, tariffCode);
    }

    protected ApplyRequest applyRequest(String requestId, String enterpriseId, long quantity) {
        return new ApplyRequest(requestId, enterpriseId, "WH-A", "WH-B",
                List.of(new ItemRequest(TARIFF_A, quantity, new BigDecimal("12.50"))));
    }

    protected ApplyRequest twoItemApplyRequest(String requestId, String enterpriseId,
            long quantityA, long quantityB) {
        return new ApplyRequest(requestId, enterpriseId, "WH-A", "WH-B", List.of(
                new ItemRequest(TARIFF_A, quantityA, new BigDecimal("12.50")),
                new ItemRequest(TARIFF_B, quantityB, new BigDecimal("99.00"))));
    }

    protected String statusOf(long orderId) {
        return jdbc.queryForObject("SELECT status FROM transfer_order WHERE id = ?",
                String.class, orderId);
    }

    protected long verifiedOf(long orderId, String tariffCode) {
        return jdbc.queryForObject("""
                SELECT verified_quantity FROM transfer_order_item
                WHERE order_id = ? AND tariff_code = ?
                """, Long.class, orderId, tariffCode);
    }

    protected boolean isFrozen(String enterpriseId) {
        List<Boolean> rows = jdbc.query(
                "SELECT frozen FROM enterprise_account WHERE enterprise_id = ?",
                (rs, n) -> rs.getBoolean(1), enterpriseId);
        return !rows.isEmpty() && rows.get(0);
    }
}
