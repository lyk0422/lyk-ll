package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 作物与生育期窗口数据访问。
 */
@Repository
public class CropRepository {

    private final JdbcTemplate jdbcTemplate;

    public CropRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 作物生育期允许窗口（边界含等号）。温度单位摄氏度，湿度单位 %RH。
     */
    public record StageWindow(
            String cropCode,
            String stageCode,
            String stageName,
            BigDecimal dayTempMin,
            BigDecimal dayTempMax,
            BigDecimal nightTempMin,
            BigDecimal nightTempMax,
            BigDecimal humidityMin,
            BigDecimal humidityMax) {
    }

    private static final RowMapper<StageWindow> STAGE_MAPPER = (rs, n) -> new StageWindow(
            rs.getString("crop_code"),
            rs.getString("stage_code"),
            rs.getString("stage_name"),
            rs.getBigDecimal("day_temp_min"),
            rs.getBigDecimal("day_temp_max"),
            rs.getBigDecimal("night_temp_min"),
            rs.getBigDecimal("night_temp_max"),
            rs.getBigDecimal("humidity_min"),
            rs.getBigDecimal("humidity_max"));

    /**
     * 合成造数：按编码幂等写入作物及其全部生育期窗口（先清旧窗口再写入）。
     */
    public void upsertCrop(String cropCode, String cropName, Instant now, List<StageWindow> stages) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM crop WHERE crop_code = ?", Integer.class, cropCode);
        if (existing == null || existing == 0) {
            jdbcTemplate.update("INSERT INTO crop (crop_code, crop_name, created_at) VALUES (?, ?, ?)",
                    cropCode, cropName, Timestamp.from(now));
        } else {
            jdbcTemplate.update("UPDATE crop SET crop_name = ? WHERE crop_code = ?", cropName, cropCode);
        }
        // 子表整体重写，保证造数可重复执行。
        jdbcTemplate.update("DELETE FROM crop_stage WHERE crop_code = ?", cropCode);
        jdbcTemplate.batchUpdate("INSERT INTO crop_stage (crop_code, stage_code, stage_name, "
                        + "day_temp_min, day_temp_max, night_temp_min, night_temp_max, humidity_min, humidity_max) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                stages.stream().map(s -> new Object[]{
                        cropCode, s.stageCode(), s.stageName(),
                        s.dayTempMin(), s.dayTempMax(), s.nightTempMin(), s.nightTempMax(),
                        s.humidityMin(), s.humidityMax()}).toList());
    }

    public boolean existsCrop(String cropCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM crop WHERE crop_code = ?", Integer.class, cropCode);
        return count != null && count > 0;
    }

    public Optional<StageWindow> findStage(String cropCode, String stageCode) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT crop_code, stage_code, stage_name, day_temp_min, day_temp_max, "
                            + "night_temp_min, night_temp_max, humidity_min, humidity_max "
                            + "FROM crop_stage WHERE crop_code = ? AND stage_code = ?",
                    STAGE_MAPPER, cropCode, stageCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
