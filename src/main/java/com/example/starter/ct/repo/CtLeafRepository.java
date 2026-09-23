package com.example.starter.ct.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 日志叶条目数据访问（H2，参数化 SQL）。
 */
@Repository
public class CtLeafRepository {

    private static final RowMapper<CtLeafRow> ROW_MAPPER = (rs, rowNum) -> new CtLeafRow(
            rs.getLong("leaf_index"), rs.getString("request_id"), rs.getString("entry_hash"),
            rs.getString("leaf_hash"), rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public CtLeafRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 叶条目行。哈希均为小写十六进制；createdAt 为 UTC 纪元毫秒。
     */
    public record CtLeafRow(long leafIndex, String requestId, String entryHash, String leafHash, long createdAt) {
    }

    public void insert(long leafIndex, String requestId, String entryHash, String leafHash, long createdAt) {
        jdbc.update("INSERT INTO ct_leaf (leaf_index, request_id, entry_hash, leaf_hash, created_at)"
                + " VALUES (?, ?, ?, ?, ?)", leafIndex, requestId, entryHash, leafHash, createdAt);
    }

    public Optional<CtLeafRow> findByRequestId(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT leaf_index, request_id, entry_hash, leaf_hash, created_at FROM ct_leaf"
                            + " WHERE request_id = ?",
                    ROW_MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<CtLeafRow> findByIndex(long leafIndex) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT leaf_index, request_id, entry_hash, leaf_hash, created_at FROM ct_leaf"
                            + " WHERE leaf_index = ?",
                    ROW_MAPPER, leafIndex));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 当前已提交的叶数量（即当前树大小）。
     */
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ct_leaf", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 按索引升序读取前 treeSize 条叶哈希。
     */
    public List<String> findLeafHashesUpTo(long treeSize) {
        return jdbc.queryForList(
                "SELECT leaf_hash FROM ct_leaf WHERE leaf_index < ? ORDER BY leaf_index ASC",
                String.class, treeSize);
    }
}
