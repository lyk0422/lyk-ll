package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.BlastZone;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 爆区与监测测点数据访问。
 */
@Repository
public class ZoneRepository {

    private final JdbcTemplate jdbc;

    public ZoneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询爆区。
     */
    public Optional<BlastZone> findById(long id) {
        return jdbc.query("SELECT * FROM blast_zone WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    /**
     * 查询爆区配置的全部监测测点编码。
     */
    public List<String> findPointCodes(long zoneId) {
        return jdbc.queryForList(
                "SELECT point_code FROM monitor_point WHERE zone_id = ? ORDER BY point_code",
                String.class, zoneId);
    }

    private BlastZone map(ResultSet rs, int rowNum) throws SQLException {
        return new BlastZone(
                rs.getLong("id"),
                rs.getLong("mine_id"),
                rs.getString("name"),
                rs.getBigDecimal("max_charge_per_delay"));
    }
}
