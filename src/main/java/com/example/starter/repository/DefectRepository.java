package com.example.starter.repository;

import com.example.starter.model.DefectLevel;
import com.example.starter.model.DefectRow;
import com.example.starter.model.RecurrenceAgg;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 缺陷记录数据访问：缺陷登记后只允许查询，不允许被维修记录覆盖。
 */
@Repository
public class DefectRepository {

    private static final RowMapper<DefectRow> ROW_MAPPER = (rs, n) -> new DefectRow(
            rs.getLong("id"),
            rs.getLong("blade_id"),
            rs.getString("blade_position"),
            DefectLevel.valueOf(rs.getString("defect_level")),
            rs.getString("defect_type"),
            rs.getObject("detected_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public DefectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记缺陷记录，返回自增主键。
     */
    public long insert(long bladeId, String bladePosition, DefectLevel level, String defectType,
                       LocalDateTime detectedAt, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO defect_record(blade_id, blade_position, defect_level, defect_type, detected_at, created_at)"
                            + " VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, bladeId);
            ps.setString(2, bladePosition);
            ps.setString(3, level.name());
            ps.setString(4, defectType);
            ps.setTimestamp(5, Timestamp.valueOf(detectedAt));
            ps.setTimestamp(6, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询缺陷。
     */
    public Optional<DefectRow> findById(long id) {
        List<DefectRow> rows = jdbc.query(
                "SELECT id, blade_id, blade_position, defect_level, defect_type, detected_at"
                        + " FROM defect_record WHERE id = ?",
                ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 统计同叶片同类缺陷在 [from, to] 窗口内的登记次数（含边界）。
     */
    public long countSameTypeWithin(long bladeId, String defectType,
                                    LocalDateTime from, LocalDateTime to) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM defect_record"
                        + " WHERE blade_id = ? AND defect_type = ? AND detected_at >= ? AND detected_at <= ?",
                Long.class, bladeId, defectType, Timestamp.valueOf(from), Timestamp.valueOf(to));
        return count == null ? 0 : count;
    }

    /**
     * 是否存在未闭环的结构级缺陷（无已结案维修单的结构级缺陷）。
     */
    public boolean existsUnresolvedStructural(long bladeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM defect_record d"
                        + " WHERE d.blade_id = ? AND d.defect_level = 'STRUCTURAL'"
                        + " AND NOT EXISTS (SELECT 1 FROM repair_order r"
                        + " WHERE r.defect_id = d.id AND r.status = 'CLOSED')",
                Integer.class, bladeId);
        return count != null && count > 0;
    }

    /**
     * 复发统计：窗口内按缺陷类别聚合，供复发统计复算。
     */
    public List<RecurrenceAgg> aggregateByTypeWithin(long bladeId,
                                                     LocalDateTime from, LocalDateTime to) {
        return jdbc.query(
                "SELECT defect_type, COUNT(*) AS cnt, MIN(detected_at) AS first_at, MAX(detected_at) AS last_at"
                        + " FROM defect_record"
                        + " WHERE blade_id = ? AND detected_at >= ? AND detected_at <= ?"
                        + " GROUP BY defect_type ORDER BY defect_type",
                (rs, n) -> new RecurrenceAgg(
                        rs.getString("defect_type"),
                        rs.getLong("cnt"),
                        rs.getObject("first_at", LocalDateTime.class),
                        rs.getObject("last_at", LocalDateTime.class)),
                bladeId, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }
}
