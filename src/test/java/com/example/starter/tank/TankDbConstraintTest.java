package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 储罐计量数据库约束（H2 MODE=MySQL）真实校验：CHECK 守恒与唯一约束在数据库层兜底，
 * 不依赖服务层或内存 Map。
 */
class TankDbConstraintTest extends AbstractIntegrationTest {

    @Test
    void h2RejectsSafetyReserveNotBelowCapacityAtDatabaseLevel() {
        // 安全储备恰等于容量违反 0≤安全储备<容量 的 CHECK，数据库层直接拒绝
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO odorant_tank
                            (station_id, tank_code, capacity_kg, safety_reserve_kg, balance_kg,
                             reserved_kg, version, created_at, updated_at)
                        VALUES (1, 'BAD-TK', 20.000, 20.000, 0.000, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """));
        assertEquals(0L, count("SELECT COUNT(*) FROM odorant_tank WHERE tank_code = 'BAD-TK'"));
    }

    @Test
    void h2RejectsNegativeBalanceAndReserveOverBalance() {
        jdbc.update("""
                INSERT INTO odorant_tank
                    (station_id, tank_code, capacity_kg, safety_reserve_kg, balance_kg,
                     reserved_kg, version, created_at, updated_at)
                VALUES (1, 'OK-TK', 100.000, 0.000, 10.000, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        // 余额不得为负
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE odorant_tank SET balance_kg = -0.001 WHERE tank_code = 'OK-TK'"));
        // 预占不得大于余额
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE odorant_tank SET reserved_kg = 10.001, balance_kg = 10.000 "
                        + "WHERE tank_code = 'OK-TK'"));
    }

    @Test
    void h2RejectsDuplicateTransportBatchAtDatabaseLevel() {
        jdbc.update("""
                INSERT INTO odorant_tank
                    (station_id, tank_code, capacity_kg, safety_reserve_kg, balance_kg,
                     reserved_kg, version, created_at, updated_at)
                VALUES (1, 'B-TK', 100.000, 0.000, 0.000, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        String insertOrder = """
                INSERT INTO replenishment_order
                    (station_id, tank_id, request_id, transport_batch_no, quantity_kg,
                     accepted_concentration_mg_m3, first_handler, second_handler,
                     balance_before_kg, balance_after_kg, biz_time, created_at)
                VALUES (1, 1, ?, 'UNIQ-BATCH', 1.000, 20.000, 'H01', 'H02', 0, 1,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;
        jdbc.update(insertOrder, "RQ-1");
        // 同批号不同请求号：唯一约束兜底
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insertOrder, "RQ-2"));
        assertEquals(1L, count("SELECT COUNT(*) FROM replenishment_order WHERE transport_batch_no = 'UNIQ-BATCH'"));
    }
}
