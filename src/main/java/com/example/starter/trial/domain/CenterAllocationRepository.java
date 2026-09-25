package com.example.starter.trial.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 中心区组分配状态仓储。入组事务内通过 SELECT ... FOR UPDATE 锁定中心状态行，
 * 使同一中心的并發入组在数据库层串行化，区组位置与排列不会被并发破坏。
 */
@Repository
public class CenterAllocationRepository {

    private final JdbcTemplate jdbcTemplate;

    public CenterAllocationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 中心锁定视图：合成种子、盲态映射与区组状态。
     */
    public record CenterState(
            long randomSeed,
            String maskAGroup,
            long currentBlockNo,
            int allocatedInBlock,
            String currentBlockPattern,
            String prevBlockPattern) {
    }

    /**
     * 锁定并读取中心状态。必须运行在事务中；中心不存在返回空。
     */
    public Optional<CenterState> lockForUpdate(String trialCode, String centerCode) {
        return jdbcTemplate.query(
                "SELECT s.random_seed, s.mask_a_group, a.current_block_no, a.allocated_in_block, "
                        + "a.current_block_pattern, a.prev_block_pattern "
                        + "FROM trial_site s "
                        + "JOIN center_allocation_state a "
                        + "ON a.trial_code = s.trial_code AND a.center_code = s.center_code "
                        + "WHERE s.trial_code = ? AND s.center_code = ? FOR UPDATE",
                rs -> {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new CenterState(
                            rs.getLong("random_seed"),
                            rs.getString("mask_a_group"),
                            rs.getLong("current_block_no"),
                            rs.getInt("allocated_in_block"),
                            rs.getString("current_block_pattern"),
                            rs.getString("prev_block_pattern")));
                }, trialCode, centerCode);
    }

    /**
     * 推进区组状态。{@code allocatedInBlock} 为推进后的值（1-4 语义上，落库时满 4 归零进位）。
     */
    public void advance(String trialCode, String centerCode, long newBlockNo, int newAllocated,
                        String currentPattern, String prevPattern, Instant updatedAt) {
        jdbcTemplate.update(
                "UPDATE center_allocation_state SET current_block_no = ?, allocated_in_block = ?, "
                        + "current_block_pattern = ?, prev_block_pattern = ?, updated_at_utc = ? "
                        + "WHERE trial_code = ? AND center_code = ?",
                newBlockNo, newAllocated, currentPattern, prevPattern,
                Timestamp.from(updatedAt), trialCode, centerCode);
    }
}
