package com.example.starter.service;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.domain.DefectLevel;
import com.example.starter.error.BadRequestException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 合成分级：按 grading_rule 表（缺陷类型 + 深度占壁厚比）定级，
 * 精确类型规则优先于通配规则，同类型内按优先级取首个覆盖深度比的规则。
 */
@Service
public class GradingService {

    private final JdbcClient jdbc;

    public GradingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 计算缺陷分级；无任何规则覆盖时拒绝登记。
     */
    public DefectLevel grade(String defectType, BigDecimal depthRatio) {
        List<Rule> rules = jdbc.sql("""
                        SELECT max_depth_ratio, level FROM grading_rule
                        WHERE defect_type = ? OR defect_type = '*'
                        ORDER BY CASE WHEN defect_type = '*' THEN 1 ELSE 0 END, priority
                        """)
                .param(defectType)
                .query((rs, rowNum) -> new Rule(rs.getBigDecimal("max_depth_ratio"),
                        DefectLevel.valueOf(rs.getString("level"))))
                .list();
        for (Rule rule : rules) {
            if (rule.maxDepthRatio() == null || depthRatio.compareTo(rule.maxDepthRatio()) <= 0) {
                return rule.level();
            }
        }
        throw new BadRequestException("合成分级表无匹配规则，缺陷类型: " + defectType);
    }

    private record Rule(BigDecimal maxDepthRatio, DefectLevel level) {
    }
}
