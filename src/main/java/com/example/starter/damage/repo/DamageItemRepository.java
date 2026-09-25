package com.example.starter.damage.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.damage.repo.po.DamageItemPo;

/** 残损清单与检验记录数据访问。 */
@Repository
public class DamageItemRepository {

    private final JdbcTemplate jdbc;

    public DamageItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertSurvey(long caseId, String requestId, String surveyor,
                             long surveyedAtUtc, boolean lateEvent, long nowUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO survey (case_id, request_id, surveyor, surveyed_at_utc, late_event, created_at_utc)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, caseId);
            ps.setString(2, requestId);
            ps.setString(3, surveyor);
            ps.setLong(4, surveyedAtUtc);
            ps.setBoolean(5, lateEvent);
            ps.setLong(6, nowUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public long insertDamageItem(long caseId, long surveyId, String code, String location,
                                 String responsibility, String repairCode, String repairName,
                                 java.math.BigDecimal unitCost, int quantity,
                                 java.math.BigDecimal amount, long nowUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO damage_item
                        (case_id, survey_id, code, location, responsibility, repair_code,
                         repair_name, unit_cost, quantity, amount, created_at_utc)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, caseId);
            ps.setLong(2, surveyId);
            ps.setString(3, code);
            ps.setString(4, location);
            ps.setString(5, responsibility);
            ps.setString(6, repairCode);
            ps.setString(7, repairName);
            ps.setBigDecimal(8, unitCost);
            ps.setInt(9, quantity);
            ps.setBigDecimal(10, amount);
            ps.setLong(11, nowUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<DamageItemPo> findByCaseId(long caseId) {
        return jdbc.query("""
                SELECT id, case_id, survey_id, code, location, responsibility, repair_code,
                       repair_name, unit_cost, quantity, amount, created_at_utc
                FROM damage_item WHERE case_id = ? ORDER BY id
                """, (rs, n) -> new DamageItemPo(
                rs.getLong("id"),
                rs.getLong("case_id"),
                rs.getLong("survey_id"),
                rs.getString("code"),
                rs.getString("location"),
                rs.getString("responsibility"),
                rs.getString("repair_code"),
                rs.getString("repair_name"),
                rs.getBigDecimal("unit_cost"),
                rs.getInt("quantity"),
                rs.getBigDecimal("amount"),
                (Long) rs.getObject("created_at_utc")), caseId);
    }
}
