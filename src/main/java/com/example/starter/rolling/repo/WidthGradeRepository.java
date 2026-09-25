package com.example.starter.rolling.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合成宽度档位表访问：板宽毫米 -> 档位序号（档位序号越大板越窄）。
 */
@Repository
public class WidthGradeRepository {

    private final JdbcTemplate jdbc;

    public WidthGradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 返回 板宽毫米 -> 档位号 映射。
     */
    public Map<Integer, Integer> loadGrades() {
        Map<Integer, Integer> grades = new LinkedHashMap<>();
        jdbc.query("SELECT width_mm, grade_no FROM width_grade", rs -> {
            grades.put(rs.getInt("width_mm"), rs.getInt("grade_no"));
        });
        return Map.copyOf(grades);
    }
}
