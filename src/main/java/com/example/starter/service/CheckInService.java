package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.example.starter.support.Fingerprints;
import com.example.starter.support.NotFoundException;
import com.example.starter.web.dto.CheckInRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;

/**
 * 学生上下车打卡服务：按学生与日期去重（唯一约束兜底），
 * 重复打卡返回首次记录；写操作按 requestId 幂等。
 */
@Service
public class CheckInService {

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public CheckInService(JdbcTemplate jdbc, IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 打卡（幂等 + 学生/日期去重）。
     */
    @Transactional
    public CheckInRequest.Result checkIn(CheckInRequest req) {
        requireExists("route", req.routeId());
        requireExists("student", req.studentId());
        LocalDate checkDate = req.checkDate() != null ? req.checkDate() : LocalDate.now(clock);
        String fingerprint = Fingerprints.of(List.of(
                req.requestId(),
                String.valueOf(req.routeId()),
                String.valueOf(req.studentId()),
                checkDate.toString()));
        return idempotency.execute(req.requestId(), fingerprint, CheckInRequest.Result.class,
                () -> doCheckIn(req, checkDate));
    }

    private CheckInRequest.Result doCheckIn(CheckInRequest req, LocalDate checkDate) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO check_in (route_id, student_id, check_date, request_id) VALUES (?,?,?,?)",
                        new String[]{"id"});
                ps.setLong(1, req.routeId());
                ps.setLong(2, req.studentId());
                ps.setObject(3, checkDate);
                ps.setString(4, req.requestId());
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("打卡记录主键生成失败");
            }
            return new CheckInRequest.Result(key.longValue(), req.routeId(), req.studentId(), checkDate, false);
        } catch (DuplicateKeyException e) {
            // 学生+日期去重：返回首次打卡记录
            return jdbc.query(
                    "SELECT id, route_id FROM check_in WHERE student_id = ? AND check_date = ?",
                    rs -> {
                        if (!rs.next()) {
                            throw new IllegalStateException("去重后未找到首次打卡记录");
                        }
                        return new CheckInRequest.Result(rs.getLong(1), rs.getLong(2),
                                req.studentId(), checkDate, true);
                    },
                    req.studentId(), checkDate);
        }
    }

    private void requireExists(String table, Long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?",
                Integer.class, id);
        if (count == null || count == 0) {
            throw new NotFoundException(table + " 不存在: " + id);
        }
    }
}
