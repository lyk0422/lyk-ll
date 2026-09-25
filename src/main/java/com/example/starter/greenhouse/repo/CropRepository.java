package com.example.starter.greenhouse.repo;

import com.example.starter.greenhouse.po.Crop;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/**
 * 作物生育期窗口数据访问。
 */
@Repository
public class CropRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public CropRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("crop").usingGeneratedKeyColumns("id");
    }

    /** 新增作物（crop_code 唯一，重复由上层转 409）。 */
    public Crop insert(Crop crop) {
        var params = new java.util.HashMap<String, Object>();
        params.put("crop_code", crop.getCropCode());
        params.put("name", crop.getName());
        params.put("stage_code", crop.getStageCode());
        params.put("day_temp_min", crop.getDayTempMin());
        params.put("day_temp_max", crop.getDayTempMax());
        params.put("day_humidity_min", crop.getDayHumidityMin());
        params.put("day_humidity_max", crop.getDayHumidityMax());
        params.put("night_temp_min", crop.getNightTempMin());
        params.put("night_temp_max", crop.getNightTempMax());
        params.put("night_humidity_min", crop.getNightHumidityMin());
        params.put("night_humidity_max", crop.getNightHumidityMax());
        params.put("created_at", java.sql.Timestamp.from(crop.getCreatedAt()));
        Number key = insert.executeAndReturnKey(params);
        crop.setId(key.longValue());
        return crop;
    }

    public Optional<Crop> findByCode(String cropCode) {
        return jdbc.query("SELECT * FROM crop WHERE crop_code = ?", MAPPER, cropCode)
                .stream().findFirst();
    }

    public Optional<Crop> findById(Long id) {
        return jdbc.query("SELECT * FROM crop WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    static final RowMapper<Crop> MAPPER = (ResultSet rs, int rowNum) -> {
        Crop c = new Crop();
        c.setId(rs.getLong("id"));
        c.setCropCode(rs.getString("crop_code"));
        c.setName(rs.getString("name"));
        c.setStageCode(rs.getString("stage_code"));
        c.setDayTempMin(rs.getBigDecimal("day_temp_min"));
        c.setDayTempMax(rs.getBigDecimal("day_temp_max"));
        c.setDayHumidityMin(rs.getBigDecimal("day_humidity_min"));
        c.setDayHumidityMax(rs.getBigDecimal("day_humidity_max"));
        c.setNightTempMin(rs.getBigDecimal("night_temp_min"));
        c.setNightTempMax(rs.getBigDecimal("night_temp_max"));
        c.setNightHumidityMin(rs.getBigDecimal("night_humidity_min"));
        c.setNightHumidityMax(rs.getBigDecimal("night_humidity_max"));
        c.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return c;
    };
}
