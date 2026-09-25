package com.example.starter.db;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 风机台账与占用数据访问。风机锁定依赖 fan_active 表 fan_id 主键冲突保证并发唯一占用。
 */
@Repository
public class FanRepository {

    private static final RowMapper<FanPo> MAPPER = (rs, n) -> new FanPo(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public FanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FanPo> findByCode(String code) {
        List<FanPo> rows = jdbc.query("SELECT * FROM fan WHERE code = ?", MAPPER, code);
        return rows.stream().findFirst();
    }

    public Optional<FanPo> findById(long id) {
        List<FanPo> rows = jdbc.query("SELECT * FROM fan WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public FanPo insert(String code, String name) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO fan (code, name, created_at) VALUES (?, ?, ?)",
                code, name, Timestamp.valueOf(now));
        return findByCode(code).orElseThrow();
    }

    /**
     * 尝试锁定风机；已被占用返回 false（fan_active 主键冲突即代表风机已锁定）。
     */
    public boolean tryLock(long fanId, long operationId, long siloId) {
        try {
            jdbc.update("INSERT INTO fan_active (fan_id, operation_id, silo_id, locked_at) VALUES (?, ?, ?, ?)",
                    fanId, operationId, siloId, Timestamp.valueOf(LocalDateTime.now()));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public void unlock(long fanId) {
        jdbc.update("DELETE FROM fan_active WHERE fan_id = ?", fanId);
    }

    public boolean isLocked(long fanId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM fan_active WHERE fan_id = ?",
                Integer.class, fanId);
        return count != null && count > 0;
    }
}
