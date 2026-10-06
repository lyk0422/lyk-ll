package com.example.starter.repo;

import java.sql.Statement;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 作业回传数据访问。
 */
@Repository
public class ReportRepository {

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long permitId, String beforePhoto, String afterPhoto,
                       String disposalRecord, LocalDateTime reportedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO work_report(permit_id, before_photo, after_photo, disposal_record, reported_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, permitId);
            ps.setString(2, beforePhoto);
            ps.setString(3, afterPhoto);
            ps.setString(4, disposalRecord);
            ps.setObject(5, reportedAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM work_report", Long.class);
    }
}
