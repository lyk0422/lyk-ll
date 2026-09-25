package com.example.starter.calibrator;

import java.time.Instant;
import java.sql.Timestamp;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import com.example.starter.error.ApiExceptionHandler.NotFoundException;

/**
 * 活度计与日检参考源登记（合成数据）。
 */
@Service
public class CalibratorService {

    private final JdbcClient jdbc;

    public CalibratorService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 活度计视图。
     *
     * @param id   主键
     * @param code 活度计编号
     * @param name 活度计名称
     */
    public record CalibratorView(long id, String code, String name) {
    }

    /**
     * 参考源视图。
     *
     * @param id           主键
     * @param calibratorId 所属活度计
     * @param nuclideId    核素
     * @param activityMbq  标定活度，单位 MBq
     * @param measuredAt   标定时刻（UTC）
     */
    public record ReferenceSourceView(long id, long calibratorId, long nuclideId,
                                      double activityMbq, Instant measuredAt) {
    }

    /**
     * 登记活度计。
     */
    public CalibratorView registerCalibrator(String code, String name) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dose_calibrator (code, name) VALUES (?, ?)")
                .params(code, name)
                .update(keyHolder);
        return new CalibratorView(keyHolder.getKey().longValue(), code, name);
    }

    /**
     * 登记日检参考源。
     */
    public ReferenceSourceView registerReferenceSource(long calibratorId, long nuclideId,
                                                       double activityMbq, Instant measuredAt) {
        requireCalibrator(calibratorId);
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO reference_source (calibrator_id, nuclide_id, activity_mbq, measured_at)"
                        + " VALUES (?, ?, ?, ?)")
                .params(calibratorId, nuclideId, activityMbq, Timestamp.from(measuredAt))
                .update(keyHolder);
        return new ReferenceSourceView(keyHolder.getKey().longValue(), calibratorId, nuclideId,
                activityMbq, measuredAt);
    }

    /**
     * 按主键查询活度计，不存在抛 404。
     */
    public CalibratorView requireCalibrator(long id) {
        return jdbc.sql("SELECT id, code, name FROM dose_calibrator WHERE id = ?")
                .param(id)
                .query((rs, rn) -> new CalibratorView(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name")))
                .optional()
                .orElseThrow(() -> new NotFoundException("活度计不存在: " + id));
    }

    /**
     * 按主键查询参考源，不存在抛 404。
     */
    public ReferenceSourceView requireReferenceSource(long id) {
        return jdbc.sql("SELECT id, calibrator_id, nuclide_id, activity_mbq, measured_at"
                        + " FROM reference_source WHERE id = ?")
                .param(id)
                .query((rs, rn) -> new ReferenceSourceView(rs.getLong("id"), rs.getLong("calibrator_id"),
                        rs.getLong("nuclide_id"), rs.getDouble("activity_mbq"),
                        rs.getTimestamp("measured_at").toInstant()))
                .optional()
                .orElseThrow(() -> new NotFoundException("参考源不存在: " + id));
    }
}
