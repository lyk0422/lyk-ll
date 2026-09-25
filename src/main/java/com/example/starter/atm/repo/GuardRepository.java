package com.example.starter.atm.repo;

import com.example.starter.atm.domain.Guard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 押运员主数据访问（合成数据）。
 */
@Repository
public class GuardRepository {

    private static final RowMapper<Guard> MAPPER = (rs, n) -> new Guard(
            rs.getLong("id"),
            rs.getString("guard_no"),
            rs.getString("name"),
            rs.getInt("frozen") == 1,
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public GuardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Guard findByNo(String guardNo) {
        List<Guard> list = jdbc.query(
                "SELECT * FROM guard WHERE guard_no = ?", MAPPER, guardNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public Guard findById(long id) {
        List<Guard> list = jdbc.query(
                "SELECT * FROM guard WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public long insert(String guardNo, String name, long createdAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO guard (guard_no, name, frozen, created_at) VALUES (?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, guardNo);
            ps.setString(2, name);
            ps.setLong(3, createdAt);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    /** 长短款逾期升级：冻结责任人，禁止其接新押运打卡任务。 */
    public int markFrozen(long guardId) {
        return jdbc.update("UPDATE guard SET frozen = 1 WHERE id = ?", guardId);
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM guard", Long.class);
        return c == null ? 0 : c;
    }
}
