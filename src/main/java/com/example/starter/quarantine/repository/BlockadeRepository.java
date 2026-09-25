package com.example.starter.quarantine.repository;

import com.example.starter.quarantine.domain.BlockadePo;
import com.example.starter.quarantine.domain.BlockadeStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 疫点封锁仓储。
 */
@Repository
public class BlockadeRepository {

    private static final RowMapper<BlockadePo> ROW_MAPPER = BlockadeRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public BlockadeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(BlockadePo po) {
        jdbcTemplate.update(
                "INSERT INTO blockade (blockade_id, farm_id, reason, status, effective_at, fingerprint,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.getBlockadeId(), po.getFarmId(), po.getReason(), po.getStatus().name(),
                Timestamp.from(po.getEffectiveAt()), po.getFingerprint(),
                Timestamp.from(po.getCreatedAt()), Timestamp.from(po.getUpdatedAt()));
    }

    public BlockadePo findById(String blockadeId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, blockade_id, farm_id, reason, status, effective_at, fingerprint, created_at,"
                            + " updated_at FROM blockade WHERE blockade_id = ?",
                    ROW_MAPPER, blockadeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 养殖场是否存在生效中的封锁。 */
    public boolean isFarmBlocked(String farmId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM blockade WHERE farm_id = ? AND status = 'ACTIVE'",
                Integer.class, farmId);
        return count != null && count > 0;
    }

    public BlockadePo findActiveByFarm(String farmId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, blockade_id, farm_id, reason, status, effective_at, fingerprint, created_at,"
                            + " updated_at FROM blockade WHERE farm_id = ? AND status = 'ACTIVE'"
                            + " ORDER BY effective_at DESC LIMIT 1",
                    ROW_MAPPER, farmId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private static BlockadePo mapRow(ResultSet rs, int n) throws SQLException {
        BlockadePo po = new BlockadePo();
        po.setId(rs.getLong("id"));
        po.setBlockadeId(rs.getString("blockade_id"));
        po.setFarmId(rs.getString("farm_id"));
        po.setReason(rs.getString("reason"));
        po.setStatus(BlockadeStatus.valueOf(rs.getString("status")));
        po.setEffectiveAt(rs.getTimestamp("effective_at").toInstant());
        po.setFingerprint(rs.getString("fingerprint"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        po.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return po;
    }
}
