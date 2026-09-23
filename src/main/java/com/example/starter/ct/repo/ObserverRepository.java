package com.example.starter.ct.repo;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 观察者状态与上报记录数据访问（H2，参数化 SQL）。
 */
@Repository
public class ObserverRepository {

    private static final RowMapper<ObserverStateRow> STATE_MAPPER = (rs, rowNum) -> new ObserverStateRow(
            rs.getString("observer_id"), rs.getLong("max_tree_size"), rs.getString("last_root_hash"),
            rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;

    public ObserverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 观察者最新位置。
     */
    public record ObserverStateRow(String observerId, long maxTreeSize, String lastRootHash, long updatedAt) {
    }

    public Optional<ObserverStateRow> findState(String observerId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT observer_id, max_tree_size, last_root_hash, updated_at FROM observer_state"
                            + " WHERE observer_id = ?",
                    STATE_MAPPER, observerId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 插入或推进观察者位置；仅当 newTreeSize 更大时更新，较小树头不倒退。
     */
    public void upsertState(String observerId, long newTreeSize, String rootHash, long now) {
        int updated = jdbc.update(
                "UPDATE observer_state SET max_tree_size = ?, last_root_hash = ?, updated_at = ?"
                        + " WHERE observer_id = ? AND max_tree_size < ?",
                newTreeSize, rootHash, now, observerId, newTreeSize);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO observer_state (observer_id, max_tree_size, last_root_hash, updated_at)"
                        + " VALUES (?, ?, ?, ?)", observerId, newTreeSize, rootHash, now);
            } catch (DuplicateKeyException e) {
                // 并发首次上报：重试条件更新，仍保证不倒退。
                jdbc.update(
                        "UPDATE observer_state SET max_tree_size = ?, last_root_hash = ?, updated_at = ?"
                                + " WHERE observer_id = ? AND max_tree_size < ?",
                        newTreeSize, rootHash, now, observerId, newTreeSize);
            }
        }
    }

    /**
     * 记录一条上报；重复上报（同观察者、同树大小、同根）返回 false。
     */
    public boolean insertReport(String observerId, long treeSize, String rootHash, long reportedAt,
            long receivedAt) {
        try {
            jdbc.update("INSERT INTO observer_report"
                    + " (observer_id, tree_size, root_hash, reported_at, received_at) VALUES (?, ?, ?, ?, ?)",
                    observerId, treeSize, rootHash, reportedAt, receivedAt);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
