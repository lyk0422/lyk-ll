package com.example.starter.bonded.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 保税仓库存数据访问。可用数量 = total_qty - frozen_qty。
 */
@Repository
public class InventoryRepository {

    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 判断库存行是否存在。
     */
    public boolean exists(String warehouseId, String hsCode) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory WHERE warehouse_id = ? AND hs_code = ?",
                Integer.class, warehouseId, hsCode);
        return cnt != null && cnt > 0;
    }

    /**
     * 原子冻结扣减：total_qty 与 frozen_qty 同时增加，仅当可用数量充足时生效，返回受影响行数。
     */
    public int freezeIfAvailable(String warehouseId, String hsCode, long qty) {
        return jdbc.update(
                "UPDATE inventory SET total_qty = total_qty - ?, frozen_qty = frozen_qty + ?"
                        + " WHERE warehouse_id = ? AND hs_code = ? AND total_qty - frozen_qty >= ?",
                qty, qty, warehouseId, hsCode, qty);
    }

    /**
     * 恢复冻结：撤销时把冻结数量加回账面。
     */
    public int unfreeze(String warehouseId, String hsCode, long qty) {
        return jdbc.update(
                "UPDATE inventory SET total_qty = total_qty + ?, frozen_qty = frozen_qty - ?"
                        + " WHERE warehouse_id = ? AND hs_code = ? AND frozen_qty >= ?",
                qty, qty, warehouseId, hsCode, qty);
    }

    /**
     * 核销落地：冻结数量转为实际出库（仅减少 frozen_qty，total_qty 已在核注时扣减）。
     */
    public int consumeFrozen(String warehouseId, String hsCode, long qty) {
        return jdbc.update(
                "UPDATE inventory SET frozen_qty = frozen_qty - ?"
                        + " WHERE warehouse_id = ? AND hs_code = ? AND frozen_qty >= ?",
                qty, warehouseId, hsCode, qty);
    }

    /**
     * 查询库存快照（total_qty, frozen_qty），不存在返回 null。
     */
    public long[] snapshot(String warehouseId, String hsCode) {
        return jdbc.query(
                "SELECT total_qty, frozen_qty FROM inventory WHERE warehouse_id = ? AND hs_code = ?",
                rs -> rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : null,
                warehouseId, hsCode);
    }
}
