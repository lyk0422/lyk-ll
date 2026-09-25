package com.example.starter.dye.repo;

import com.example.starter.dye.domain.DyeVatPO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 染缸设备仓储。占缸通过 {@code active_run_id is null} 条件更新实现并发唯一受理。
 */
@Repository
public class DyeVatRepository {

    private final JdbcTemplate jdbc;

    public DyeVatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DyeVatPO> VAT_MAPPER = (rs, n) -> {
        DyeVatPO po = new DyeVatPO();
        po.setId(rs.getLong("id"));
        po.setVatCode(rs.getString("vat_code"));
        po.setCapacityKg(rs.getBigDecimal("capacity_kg"));
        long active = rs.getLong("active_run_id");
        po.setActiveRunId(rs.wasNull() ? null : active);
        return po;
    };

    public Optional<DyeVatPO> findByCode(String vatCode) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "select id, vat_code, capacity_kg, active_run_id from dye_vat where vat_code = ?",
                    VAT_MAPPER, vatCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<DyeVatPO> findById(long id) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "select id, vat_code, capacity_kg, active_run_id from dye_vat where id = ?",
                    VAT_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void upsert(String vatCode, java.math.BigDecimal capacityKg) {
        int updated = jdbc.update("update dye_vat set capacity_kg = ? where vat_code = ?",
                capacityKg, vatCode);
        if (updated == 0) {
            jdbc.update("insert into dye_vat (vat_code, capacity_kg, active_run_id) values (?, ?, null)",
                    vatCode, capacityKg);
        }
    }

    /**
     * 条件占用：仅染缸空闲时成功。并发排缸下数据库行锁保证只有一方受理。
     * @return true 表示占用成功
     */
    public boolean occupy(long vatId, long runId) {
        return jdbc.update(
                "update dye_vat set active_run_id = ? where id = ? and active_run_id is null",
                runId, vatId) > 0;
    }

    public void release(long vatId, long runId) {
        jdbc.update("update dye_vat set active_run_id = null where id = ? and active_run_id = ?",
                vatId, runId);
    }

    public String findActiveRunNo(long runId) {
        return jdbc.queryForObject("select run_no from vat_run where id = ?", String.class, runId);
    }
}
