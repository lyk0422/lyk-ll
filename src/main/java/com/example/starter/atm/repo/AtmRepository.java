package com.example.starter.atm.repo;

import com.example.starter.atm.domain.Atm;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * ATM 主数据访问（合成数据）。
 */
@Repository
public class AtmRepository {

    private static final RowMapper<Atm> MAPPER = (rs, n) -> new Atm(
            rs.getLong("id"),
            rs.getString("atm_no"),
            rs.getString("name"),
            rs.getLong("cassette_capacity"),
            rs.getLong("avg_daily_withdrawal"),
            rs.getInt("escort_frozen") == 1,
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public AtmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Atm findByNo(String atmNo) {
        List<Atm> list = jdbc.query(
                "SELECT * FROM atm WHERE atm_no = ?", MAPPER, atmNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public Atm findById(long id) {
        List<Atm> list = jdbc.query("SELECT * FROM atm WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public long insert(String atmNo, String name, long cassetteCapacity,
                       long avgDailyWithdrawal, long createdAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO atm (atm_no, name, cassette_capacity, avg_daily_withdrawal, "
                            + "escort_frozen, created_at) VALUES (?, ?, ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, atmNo);
            ps.setString(2, name);
            ps.setLong(3, cassetteCapacity);
            ps.setLong(4, avgDailyWithdrawal);
            ps.setLong(5, createdAt);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    /** 押运异常冻结该 ATM 的后续计划编制。 */
    public int markFrozen(long atmId) {
        return jdbc.update("UPDATE atm SET escort_frozen = 1 WHERE id = ?", atmId);
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM atm", Long.class);
        return c == null ? 0 : c;
    }
}
