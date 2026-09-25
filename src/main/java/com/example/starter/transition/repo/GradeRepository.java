package com.example.starter.transition.repo;

import com.example.starter.transition.po.Grade;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 品种工艺窗口数据访问。
 */
@Repository
public class GradeRepository {

    private static final RowMapper<Grade> MAPPER = new GradeRowMapper();

    private final JdbcTemplate jdbc;

    public GradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Grade> findByCode(String gradeCode) {
        List<Grade> list = jdbc.query(
                "SELECT id, grade_code, basis_weight, ash_level, speed_min, speed_max, "
                        + "consistency_min, consistency_max, tensile_min, created_at "
                        + "FROM grade WHERE grade_code = ?",
                MAPPER, gradeCode);
        return list.stream().findFirst();
    }

    public void insert(Grade grade) {
        jdbc.update("INSERT INTO grade (grade_code, basis_weight, ash_level, speed_min, speed_max, "
                        + "consistency_min, consistency_max, tensile_min) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                grade.gradeCode, grade.basisWeight, grade.ashLevel,
                grade.speedMin, grade.speedMax,
                grade.consistencyMin, grade.consistencyMax, grade.tensileMin);
    }

    public void update(Grade grade) {
        jdbc.update("UPDATE grade SET basis_weight = ?, ash_level = ?, speed_min = ?, speed_max = ?, "
                        + "consistency_min = ?, consistency_max = ?, tensile_min = ? WHERE grade_code = ?",
                grade.basisWeight, grade.ashLevel,
                grade.speedMin, grade.speedMax,
                grade.consistencyMin, grade.consistencyMax, grade.tensileMin,
                grade.gradeCode);
    }

    public List<Grade> findAll() {
        return jdbc.query(
                "SELECT id, grade_code, basis_weight, ash_level, speed_min, speed_max, "
                        + "consistency_min, consistency_max, tensile_min, created_at FROM grade ORDER BY id",
                MAPPER);
    }

    private static class GradeRowMapper implements RowMapper<Grade> {
        @Override
        public Grade mapRow(ResultSet rs, int rowNum) throws SQLException {
            Grade g = new Grade();
            g.id = rs.getLong("id");
            g.gradeCode = rs.getString("grade_code");
            g.basisWeight = rs.getBigDecimal("basis_weight");
            g.ashLevel = rs.getInt("ash_level");
            g.speedMin = rs.getBigDecimal("speed_min");
            g.speedMax = rs.getBigDecimal("speed_max");
            g.consistencyMin = rs.getBigDecimal("consistency_min");
            g.consistencyMax = rs.getBigDecimal("consistency_max");
            g.tensileMin = rs.getBigDecimal("tensile_min");
            g.createdAt = rs.getTimestamp("created_at").toLocalDateTime();
            return g;
        }
    }
}
