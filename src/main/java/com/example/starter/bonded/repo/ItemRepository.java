package com.example.starter.bonded.repo;

import com.example.starter.bonded.domain.ItemPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 转关单商品项数据访问。
 */
@Repository
public class ItemRepository {

    private static final RowMapper<ItemPo> MAPPER = (rs, n) -> new ItemPo(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("hs_code"),
            rs.getLong("quantity"),
            rs.getBigDecimal("unit_price"),
            rs.getLong("verified_qty"));

    private final JdbcTemplate jdbc;

    public ItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批量插入商品项。
     */
    public void insertAll(Long orderId, List<ItemPo> items) {
        jdbc.batchUpdate(
                "INSERT INTO transfer_item (order_id, hs_code, quantity, unit_price, verified_qty) VALUES (?,?,?,?,0)",
                items, items.size(),
                (ps, item) -> {
                    ps.setLong(1, orderId);
                    ps.setString(2, item.hsCode());
                    ps.setLong(3, item.quantity());
                    ps.setBigDecimal(4, item.unitPrice());
                });
    }

    /**
     * 查询单据全部商品项（按税号升序，保证展示稳定）。
     */
    public List<ItemPo> findByOrderId(Long orderId) {
        return jdbc.query(
                "SELECT * FROM transfer_item WHERE order_id = ? ORDER BY hs_code", MAPPER, orderId);
    }

    /**
     * 查询单据全部商品项并加行锁（须在事务内调用）。
     */
    public List<ItemPo> findByOrderIdForUpdate(Long orderId) {
        return jdbc.query(
                "SELECT * FROM transfer_item WHERE order_id = ? ORDER BY hs_code FOR UPDATE", MAPPER, orderId);
    }

    /**
     * 原子核销：仅当 verified_qty + delta <= quantity 时累加，返回受影响行数（0 表示超量）。
     */
    public int addVerifiedIfWithinQuota(Long itemId, long delta) {
        return jdbc.update(
                "UPDATE transfer_item SET verified_qty = verified_qty + ? WHERE id = ? AND verified_qty + ? <= quantity",
                delta, itemId, delta);
    }
}
