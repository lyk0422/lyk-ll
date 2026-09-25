package com.example.starter.survey.repo;

import com.example.starter.survey.domain.ShipRow;
import com.example.starter.survey.domain.SurveyTypeRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 船舶与检验类型字典数据访问（合成数据）。
 */
@Repository
public class ShipTypeRepo {

    private final JdbcTemplate jdbc;

    public ShipTypeRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ShipRow findShipByImo(String imo) {
        List<ShipRow> rows = jdbc.query(
                "SELECT id, imo, name, anniversary_date, created_at FROM ship WHERE imo = ?",
                this::mapShip, imo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ShipRow findShipById(long id) {
        List<ShipRow> rows = jdbc.query(
                "SELECT id, imo, name, anniversary_date, created_at FROM ship WHERE id = ?",
                this::mapShip, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 船舶行级悲观锁：同船的发证/展期申请串行化。 */
    public ShipRow lockShip(long id) {
        List<ShipRow> rows = jdbc.query(
                "SELECT id, imo, name, anniversary_date, created_at FROM ship WHERE id = ? FOR UPDATE",
                this::mapShip, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ShipRow> findAllShips() {
        return jdbc.query(
                "SELECT id, imo, name, anniversary_date, created_at FROM ship ORDER BY id",
                this::mapShip);
    }

    public SurveyTypeRow findType(String code) {
        List<SurveyTypeRow> rows = jdbc.query(
                "SELECT code, name, extendable FROM survey_type WHERE code = ?",
                this::mapType, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ShipRow mapShip(ResultSet rs, int n) throws SQLException {
        LocalDate anniv = rs.getObject("anniversary_date", LocalDate.class);
        LocalDateTime createdAt = rs.getObject("created_at", LocalDateTime.class);
        return new ShipRow(rs.getLong("id"), rs.getString("imo"), rs.getString("name"),
                anniv, createdAt);
    }

    private SurveyTypeRow mapType(ResultSet rs, int n) throws SQLException {
        return new SurveyTypeRow(rs.getString("code"), rs.getString("name"),
                rs.getBoolean("extendable"));
    }
}
