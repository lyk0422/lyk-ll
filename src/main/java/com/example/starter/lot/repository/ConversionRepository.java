package com.example.starter.lot.repository;

import com.example.starter.lot.domain.ConversionRecord;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * 转换单及其输入/输出台账的数据访问（仅追加，历史不可变）。
 */
@Repository
public class ConversionRepository {

    private final JdbcTemplate jdbc;

    public ConversionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ConversionRecord> RECORD_MAPPER = (rs, rowNum) -> new ConversionRecord(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("request_json"),
            rs.getString("response_json"),
            rs.getBigDecimal("total_input_qty"),
            rs.getBigDecimal("total_output_qty"),
            rs.getBigDecimal("allowed_loss"),
            rs.getString("unit"),
            rs.getTimestamp("created_at").toLocalDateTime());

    /** 按幂等键查询已提交转换单（无锁，供幂等快速路径）。 */
    public ConversionRecord findByRequestId(String requestId) {
        List<ConversionRecord> list = jdbc.query("""
                        SELECT id, request_id, fingerprint, request_json, response_json,
                               total_input_qty, total_output_qty, allowed_loss, unit, created_at
                          FROM conversion WHERE request_id = ?
                        """,
                RECORD_MAPPER, requestId);
        return DataAccessUtils.singleResult(list);
    }

    /** 插入转换单并回填自增主键。 */
    public long insert(String requestId, String fingerprint, String requestJson,
                       BigDecimal totalInput, BigDecimal totalOutput, BigDecimal allowedLoss, String unit) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // 显式指定主键列：H2 在 MySQL 模式下会把默认时间戳列也作为生成键返回。
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO conversion
                                (request_id, fingerprint, request_json, response_json,
                                 total_input_qty, total_output_qty, allowed_loss, unit, created_at)
                            VALUES (?, ?, ?, '', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, requestJson);
            ps.setBigDecimal(4, totalInput);
            ps.setBigDecimal(5, totalOutput);
            ps.setBigDecimal(6, allowedLoss);
            ps.setString(7, unit);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("转换单主键回填失败");
        }
        return key.longValue();
    }

    /** 回填首次响应快照（同键同参重放时原样返回）。 */
    public void updateResponse(long conversionId, String responseJson) {
        jdbc.update("UPDATE conversion SET response_json = ? WHERE id = ?", responseJson, conversionId);
    }

    public void insertInput(long conversionId, int seq, String lotId, BigDecimal qty, int expectedVersion) {
        jdbc.update("""
                        INSERT INTO conversion_input (conversion_id, lot_id, seq, consume_qty, expected_version)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                conversionId, lotId, seq, qty, expectedVersion);
    }

    public void insertOutput(long conversionId, int seq, String lotId, BigDecimal qty) {
        jdbc.update("""
                        INSERT INTO conversion_output (conversion_id, lot_id, seq, output_qty)
                        VALUES (?, ?, ?, ?)
                        """,
                conversionId, lotId, seq, qty);
    }
}
