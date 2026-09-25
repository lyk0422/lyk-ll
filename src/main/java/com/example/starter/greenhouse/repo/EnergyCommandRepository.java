package com.example.starter.greenhouse.repo;

import com.example.starter.greenhouse.domain.CommandType;
import com.example.starter.greenhouse.po.EnergyCommand;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/**
 * 环控指令与能耗入账数据访问。request_id 唯一约束承载指令重放去重。
 */
@Repository
public class EnergyCommandRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public EnergyCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc)
                .withTableName("energy_command").usingGeneratedKeyColumns("id");
    }

    public EnergyCommand insert(EnergyCommand c) {
        var p = new HashMap<String, Object>();
        p.put("zone_id", c.getZoneId());
        p.put("budget_day", java.sql.Date.valueOf(c.getBudgetDay()));
        p.put("command_type", c.getCommandType().name());
        p.put("energy_kwh", c.getEnergyKwh());
        p.put("request_id", c.getRequestId());
        p.put("issued_at", Timestamp.from(c.getIssuedAt()));
        p.put("created_at", Timestamp.from(c.getCreatedAt()));
        Number key = insert.executeAndReturnKey(p);
        c.setId(key.longValue());
        return c;
    }

    public Optional<EnergyCommand> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM energy_command WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    /** 某分区某日某类指令能耗合计（kWh）。 */
    public BigDecimal sumEnergy(Long zoneId, LocalDate day, CommandType type) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(energy_kwh), 0) FROM energy_command "
                        + "WHERE zone_id = ? AND budget_day = ? AND command_type = ?",
                BigDecimal.class, zoneId, java.sql.Date.valueOf(day), type.name());
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 某分区某日某类指令条数。 */
    public int countByType(Long zoneId, LocalDate day, CommandType type) {
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_command WHERE zone_id = ? AND budget_day = ? AND command_type = ?",
                Integer.class, zoneId, java.sql.Date.valueOf(day), type.name());
        return v == null ? 0 : v;
    }

    static final RowMapper<EnergyCommand> MAPPER = (ResultSet rs, int rowNum) -> {
        EnergyCommand c = new EnergyCommand();
        c.setId(rs.getLong("id"));
        c.setZoneId(rs.getLong("zone_id"));
        c.setBudgetDay(rs.getDate("budget_day").toLocalDate());
        c.setCommandType(CommandType.valueOf(rs.getString("command_type")));
        c.setEnergyKwh(rs.getBigDecimal("energy_kwh"));
        c.setRequestId(rs.getString("request_id"));
        c.setIssuedAt(rs.getTimestamp("issued_at").toInstant());
        c.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return c;
    };
}
