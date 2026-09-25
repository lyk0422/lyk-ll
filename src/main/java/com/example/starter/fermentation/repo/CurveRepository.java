package com.example.starter.fermentation.repo;

import com.example.starter.fermentation.dto.SegmentSpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * 温度曲线版本与分段仓储：曲线版本随入罐（初始）和降温指令（切换）写入，分段可按版本复算目标温度。
 */
@Repository
public class CurveRepository {

    private final JdbcClient jdbc;

    public CurveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新增曲线版本头，返回版本主键 id。reason: INITIAL / COOLING。
     */
    public long insertVersion(long batchId, int versionNo, String reason, Long coolingCommandId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO curve_version (batch_id, version_no, reason, cooling_command_id)
                        VALUES (?, ?, ?, ?)
                        """)
                .param(batchId)
                .param(versionNo)
                .param(reason)
                .param(coolingCommandId)
                .update(keyHolder, "id");
        return keyHolder.getKey().longValue();
    }

    /**
     * 批量写入一条曲线版本的全部分段。
     */
    public void insertSegments(long versionId, List<SegmentSpec> curve) {
        for (int i = 0; i < curve.size(); i++) {
            SegmentSpec segment = curve.get(i);
            jdbc.sql("""
                            INSERT INTO curve_segment
                                (version_id, segment_seq, from_hour, to_hour, target_temp)
                            VALUES (?, ?, ?, ?, ?)
                            """)
                    .param(versionId)
                    .param(i)
                    .param(segment.fromHour())
                    .param(segment.toHour())
                    .param(segment.targetTemp())
                    .update();
        }
    }

    /**
     * 查询指定批次当前生效曲线版本下、给定相对满罐小时数命中的分段目标温度。
     * 分段按 [fromHour, toHour) 命中；未命中返回 null（调用方按业务拒绝越界读数）。
     */
    public BigDecimal findTargetTemp(long batchId, int versionNo, int elapsedHours) {
        return jdbc.sql("""
                        SELECT s.target_temp
                        FROM curve_segment s
                        JOIN curve_version v ON v.id = s.version_id
                        WHERE v.batch_id = ? AND v.version_no = ?
                          AND s.from_hour <= ? AND ? < s.to_hour
                        """)
                .param(batchId)
                .param(versionNo)
                .param(elapsedHours)
                .param(elapsedHours)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
    }

    /**
     * 查询版本内分段数量，用于复算与校验。
     */
    public int countSegments(long versionId) {
        return jdbc.sql("SELECT COUNT(*) FROM curve_segment WHERE version_id = ?")
                .param(versionId)
                .query(Integer.class)
                .single();
    }

    /**
     * 返回某版本全部分段（按顺序），供复算曲线使用。
     */
    public List<SegmentSpec> findSegments(long batchId, int versionNo) {
        return jdbc.sql("""
                        SELECT s.from_hour, s.to_hour, s.target_temp
                        FROM curve_segment s
                        JOIN curve_version v ON v.id = s.version_id
                        WHERE v.batch_id = ? AND v.version_no = ?
                        ORDER BY s.segment_seq
                        """)
                .param(batchId)
                .param(versionNo)
                .query((rs, n) -> new SegmentSpec(
                        rs.getInt("from_hour"),
                        rs.getInt("to_hour"),
                        rs.getBigDecimal("target_temp")))
                .list();
    }
}
