package com.example.starter.atm.repo;

import com.example.starter.atm.domain.CashBox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 款箱主数据访问（合成数据）。
 */
@Repository
public class CashBoxRepository {

    private static final RowMapper<CashBox> MAPPER = (rs, n) -> new CashBox(
            rs.getLong("id"),
            rs.getString("box_no"),
            rs.getLong("atm_id"),
            rs.getLong("capacity"),
            rs.getLong("loaded_amount"),
            rs.getString("status"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public CashBoxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CashBox findByNo(String boxNo) {
        List<CashBox> list = jdbc.query(
                "SELECT * FROM cash_box WHERE box_no = ?", MAPPER, boxNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public CashBox findById(long id) {
        List<CashBox> list = jdbc.query(
                "SELECT * FROM cash_box WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public long insert(String boxNo, long atmId, long capacity, long createdAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cash_box (box_no, atm_id, capacity, loaded_amount, status, created_at) "
                            + "VALUES (?, ?, ?, 0, 'AVAILABLE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, boxNo);
            ps.setLong(2, atmId);
            ps.setLong(3, capacity);
            ps.setLong(4, createdAt);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    /** 计划编制受理后将款箱置为已装填占用，金额单位：分。 */
    public int markLoaded(long boxId, long loadedAmount) {
        return jdbc.update(
                "UPDATE cash_box SET status = 'LOADED', loaded_amount = ? WHERE id = ?",
                loadedAmount, boxId);
    }
}
