package com.example.starter.transition.repo;

import com.example.starter.transition.model.Grade;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 品种工艺主数据仓储（合成数据，只读）。
 */
@Repository
public class GradeRepository {

    private static final RowMapper<Grade> MAPPER = (rs, n) -> {
        Grade g = new Grade();
        g.setGradeCode(rs.getString("grade_code"));
        g.setGradeName(rs.getString("grade_name"));
        g.setBasisWeight(rs.getBigDecimal("basis_weight"));
        g.setAshLevel(rs.getInt("ash_level"));
        g.setSpeedMin(rs.getBigDecimal("speed_min"));
        g.setSpeedMax(rs.getBigDecimal("speed_max"));
        g.setConsistencyMin(rs.getBigDecimal("consistency_min"));
        g.setConsistencyMax(rs.getBigDecimal("consistency_max"));
        return g;
    };

    private final JdbcTemplate jdbc;

    public GradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Grade> findByCode(String gradeCode) {
        List<Grade> list = jdbc.query(
                "SELECT * FROM grade WHERE grade_code = ?", MAPPER, gradeCode);
        return list.stream().findFirst();
    }
}
