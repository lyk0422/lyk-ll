package com.example.starter.staff;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 维保/施救人员数据访问（合成数据）。
 */
@Repository
public class StaffRepository {

    private final JdbcTemplate jdbc;

    public StaffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 人员记录。
     *
     * @param id       主键
     * @param name     姓名
     * @param certNo   持证编号（唯一）
     * @param createAt 创建时间（UTC）
     */
    public record Staff(long id, String name, String certNo, Instant createAt) {
    }

    private static Staff map(ResultSet rs, int rowNum) throws SQLException {
        return new Staff(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("cert_no"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    /**
     * 按持证编号查询。
     */
    public Optional<Staff> findByCertNo(String certNo) {
        return jdbc.query("SELECT * FROM staff WHERE cert_no = ?", StaffRepository::map, certNo)
                .stream().findFirst();
    }

    /**
     * 登记持证人员。
     */
    public long insert(String name, String certNo, Instant now) {
        jdbc.update("INSERT INTO staff (name, cert_no, created_at) VALUES (?, ?, ?)",
                name, certNo, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        return jdbc.queryForObject("SELECT id FROM staff WHERE cert_no = ?", Long.class, certNo);
    }
}
