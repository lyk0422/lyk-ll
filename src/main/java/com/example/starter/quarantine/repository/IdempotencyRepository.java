package com.example.starter.quarantine.repository;

import com.example.starter.quarantine.domain.IdempotentCommandPo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等命令仓储：命令键唯一，冲突时重读判定同参/异参。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotentCommandPo> ROW_MAPPER = IdempotencyRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(IdempotentCommandPo po) {
        jdbcTemplate.update(
                "INSERT INTO idempotent_command (command_key, fingerprint, result_json, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                po.getCommandKey(), po.getFingerprint(), po.getResultJson(),
                Timestamp.from(po.getCreatedAt()));
    }

    public IdempotentCommandPo find(String commandKey) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT command_key, fingerprint, result_json, created_at FROM idempotent_command"
                            + " WHERE command_key = ?",
                    ROW_MAPPER, commandKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private static IdempotentCommandPo mapRow(ResultSet rs, int n) throws SQLException {
        IdempotentCommandPo po = new IdempotentCommandPo();
        po.setCommandKey(rs.getString("command_key"));
        po.setFingerprint(rs.getString("fingerprint"));
        po.setResultJson(rs.getString("result_json"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return po;
    }
}
