package com.example.starter.dye.repo;

import com.example.starter.dye.domain.DyestuffPO;
import com.example.starter.dye.domain.StockMovementPO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 染化料主数据与库存仓储；库存扣减使用带余额条件的原子更新，杜绝超扣。
 */
@Repository
public class DyestuffRepository {

    private final JdbcTemplate jdbc;

    public DyestuffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DyestuffPO> DYESTUFF_MAPPER = (rs, n) -> {
        DyestuffPO po = new DyestuffPO();
        po.setId(rs.getLong("id"));
        po.setCode(rs.getString("code"));
        po.setName(rs.getString("name"));
        po.setStockGrams(rs.getBigDecimal("stock_grams"));
        Timestamp ts = rs.getTimestamp("updated_at");
        po.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        return po;
    };

    private static final RowMapper<StockMovementPO> MOVEMENT_MAPPER = (rs, n) -> {
        StockMovementPO po = new StockMovementPO();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setDyestuffCode(rs.getString("dyestuff_code"));
        po.setChangeGrams(rs.getBigDecimal("change_grams"));
        po.setBalanceGrams(rs.getBigDecimal("balance_grams"));
        po.setRunNo(rs.getString("run_no"));
        po.setReason(rs.getString("reason"));
        Timestamp ts = rs.getTimestamp("created_at");
        po.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return po;
    };

    public Optional<DyestuffPO> findByCode(String code) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "select id, code, name, stock_grams, updated_at from dyestuff where code = ?",
                    DYESTUFF_MAPPER, code));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 建档或整单更新库存（合成数据维护）。 */
    public void upsert(String code, String name, BigDecimal stockGrams, LocalDateTime now) {
        int updated = jdbc.update(
                "update dyestuff set name = ?, stock_grams = ?, updated_at = ? where code = ?",
                name, stockGrams, Timestamp.valueOf(now), code);
        if (updated == 0) {
            jdbc.update("insert into dyestuff (code, name, stock_grams, updated_at) values (?, ?, ?, ?)",
                    code, name, stockGrams, Timestamp.valueOf(now));
        }
    }

    /**
     * 原子扣减：仅当余额足够时生效，返回是否成功；调用方据此在同事务内登记台账。
     */
    public boolean deductStock(String code, BigDecimal grams) {
        return jdbc.update(
                "update dyestuff set stock_grams = stock_grams - ?, updated_at = updated_at "
                        + "where code = ? and stock_grams >= ?",
                grams, code, grams) > 0;
    }

    public BigDecimal getStock(String code) {
        return jdbc.queryForObject("select stock_grams from dyestuff where code = ?",
                BigDecimal.class, code);
    }

    public void insertMovement(StockMovementPO po, LocalDateTime now) {
        jdbc.update("insert into stock_movement (request_id, dyestuff_code, change_grams, balance_grams, "
                        + "run_no, reason, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                po.getRequestId(), po.getDyestuffCode(), po.getChangeGrams(), po.getBalanceGrams(),
                po.getRunNo(), po.getReason(), Timestamp.valueOf(now));
    }

    public List<StockMovementPO> findMovements(String code) {
        return jdbc.query("select id, request_id, dyestuff_code, change_grams, balance_grams, run_no, reason, "
                + "created_at from stock_movement where dyestuff_code = ? order by id", MOVEMENT_MAPPER, code);
    }

    public List<DyestuffPO> all() {
        return jdbc.query("select id, code, name, stock_grams, updated_at from dyestuff order by code",
                DYESTUFF_MAPPER);
    }
}
