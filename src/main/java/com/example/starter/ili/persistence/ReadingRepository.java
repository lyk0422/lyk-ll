package com.example.starter.ili.persistence;

import com.example.starter.ili.model.RunSide;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;

/**
 * 缺陷读数数据访问：批量插入与批次内查询。
 */
@Repository
public class ReadingRepository {

    private static final RowMapper<ReadingRow> MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            RunSide.valueOf(rs.getString("run_side")),
            rs.getString("reading_key"),
            rs.getBigDecimal("station_m"),
            rs.getBigDecimal("depth_pct"),
            rs.getBoolean("matched"));

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条读数，返回生成主键。
     */
    public long insert(long batchId, RunSide runSide, String readingKey,
                       java.math.BigDecimal stationM, java.math.BigDecimal depthPct, boolean matched) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO defect_reading (batch_id, run_side, reading_key, station_m, depth_pct, matched)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, batchId);
            ps.setString(2, runSide.name());
            ps.setString(3, readingKey);
            ps.setBigDecimal(4, stationM);
            ps.setBigDecimal(5, depthPct);
            ps.setBoolean(6, matched);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /**
     * 查询批次内全部读数，按主键排序。
     */
    public List<ReadingRow> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM defect_reading WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }
}
