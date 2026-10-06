package com.example.starter.service;

import java.time.LocalDate;
import java.util.List;

import com.example.starter.support.NotFoundException;
import com.example.starter.web.dto.MissedResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 漏乘名单服务：按线路与日期实时比对已分配学生与打卡记录，
 * 为漏乘学生生成合成通知记录（按线路+学生+日期+类型去重，重复查询不重复生成）。
 */
@Service
public class MissedRideService {

    private final JdbcTemplate jdbc;

    public MissedRideService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询漏乘名单并实时生成合成通知记录。
     */
    @Transactional
    public MissedResult missed(Long routeId, LocalDate date) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM route WHERE id = ?", Integer.class, routeId);
        if (count == null || count == 0) {
            throw new NotFoundException("线路不存在: " + routeId);
        }
        List<Long> missed = jdbc.query(
                "SELECT rs.student_id FROM route_student rs"
                        + " WHERE rs.route_id = ? AND NOT EXISTS"
                        + " (SELECT 1 FROM check_in ci WHERE ci.student_id = rs.student_id AND ci.check_date = ?)"
                        + " ORDER BY rs.student_id",
                (rs, i) -> rs.getLong(1), routeId, date);
        int generated = 0;
        for (Long studentId : missed) {
            String message = "学生 " + studentId + " 于 " + date + " 未在线路 " + routeId + " 打卡，疑似漏乘";
            try {
                jdbc.update(
                        "INSERT INTO notification (route_id, student_id, notify_date, type, message)"
                                + " VALUES (?,?,?,?,?)",
                        routeId, studentId, date, "MISSED_RIDE", message);
                generated++;
            } catch (DuplicateKeyException e) {
                // 通知记录已生成过，跳过
            }
        }
        return new MissedResult(routeId, date, missed, generated);
    }
}
