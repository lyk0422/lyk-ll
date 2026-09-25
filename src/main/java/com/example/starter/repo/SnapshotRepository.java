package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 资产负债快照数据访问。比例判定仅以已确认快照（及其后已入账变动）为准。
 */
@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long accountId, long totalAssets, long totalLiabilities, LocalDateTime snapshotAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO snapshot (account_id, total_assets, total_liabilities, confirmed, snapshot_at)"
                            + " VALUES (?, ?, ?, 1, ?)",
                    new String[]{"id"});
            ps.setLong(1, accountId);
            ps.setLong(2, totalAssets);
            ps.setLong(3, totalLiabilities);
            ps.setTimestamp(4, Timestamp.valueOf(snapshotAt));
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }
}
