package com.example.starter.ili;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * ili_defect_reading 表的数据访问。
 */
@Repository
public class DefectReadingRepository {

    private static final RowMapper<ReadingRow> MAPPER = DefectReadingRepository::mapRow;

    private final JdbcTemplate jdbc;

    public DefectReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条读数并返回生成的主键；同批次同回合 defectRef 重复时抛出 DuplicateKeyException。
     */
    public long insert(long batchId, RunSide runSide, String defectRef,
            java.math.BigDecimal absPositionM, java.math.BigDecimal depthPct, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO ili_defect_reading (batch_id, run_side, defect_ref, abs_position_m, depth_pct, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, runSide.name());
            ps.setString(3, defectRef);
            ps.setBigDecimal(4, absPositionM);
            ps.setBigDecimal(5, depthPct);
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("读数主键生成失败");
        }
        return key.longValue();
    }

    /**
     * 查询批次的全部读数，按主键升序（即写入顺序）。
     */
    public List<ReadingRow> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM ili_defect_reading WHERE batch_id = ? ORDER BY id",
                MAPPER, batchId);
    }

    /**
     * 按主键批量查询读数。
     */
    public List<ReadingRow> findByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }
        String placeholders = String.join(", ", ids.stream().map(id -> "?").toList());
        return jdbc.query("SELECT * FROM ili_defect_reading WHERE id IN (" + placeholders + ")",
                MAPPER, ids.toArray());
    }

    private static ReadingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReadingRow(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                RunSide.valueOf(rs.getString("run_side")),
                rs.getString("defect_ref"),
                rs.getBigDecimal("abs_position_m"),
                rs.getBigDecimal("depth_pct"));
    }
}
