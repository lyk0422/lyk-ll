package com.example.starter.deicing.repository;

import com.example.starter.deicing.model.DeiceType;
import com.example.starter.deicing.po.TimetablePO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 时刻表与基础资源（车位、除冰车）只读仓储。
 */
@Repository
public class TimetableRepository {

    private static final RowMapper<TimetablePO> TIMETABLE_MAPPER = (rs, rowNum) -> {
        TimetablePO po = new TimetablePO();
        po.setDeiceType(DeiceType.valueOf(rs.getString("deice_type")));
        po.setDurationMin(rs.getInt("duration_min"));
        int hot = rs.getInt("hot_min");
        po.setHotMin(rs.wasNull() ? null : hot);
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 模板
     */
    public TimetableRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param type 除冰类型
     * @return 时刻表行；不存在返回 null
     */
    public TimetablePO findByType(DeiceType type) {
        List<TimetablePO> rows = jdbcTemplate.query(
                "SELECT deice_type, duration_min, hot_min FROM deice_timetable WHERE deice_type = ?",
                TIMETABLE_MAPPER, type.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * @return 全部启用车位编号（按编号升序）
     */
    public List<String> findActiveBayIds() {
        return jdbcTemplate.queryForList(
                "SELECT bay_id FROM deicing_bay WHERE active = 1 ORDER BY bay_id", String.class);
    }

    /**
     * @return 全部启用除冰车编号（按编号升序）
     */
    public List<String> findActiveTruckIds() {
        return jdbcTemplate.queryForList(
                "SELECT truck_id FROM deicing_truck WHERE active = 1 ORDER BY truck_id", String.class);
    }
}
