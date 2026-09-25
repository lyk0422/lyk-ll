package com.example.starter.dye.repo;

import com.example.starter.dye.domain.ColorAssessmentPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 对色记录仓储。
 */
@Repository
public class ColorAssessmentRepository {

    private final JdbcTemplate jdbc;

    public ColorAssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ColorAssessmentPO> MAPPER = (rs, n) -> {
        ColorAssessmentPO po = new ColorAssessmentPO();
        po.setId(rs.getLong("id"));
        po.setRequestId(rs.getString("request_id"));
        po.setSampleCode(rs.getString("sample_code"));
        po.setStdL(rs.getBigDecimal("std_l"));
        po.setStdA(rs.getBigDecimal("std_a"));
        po.setStdB(rs.getBigDecimal("std_b"));
        po.setSampleL(rs.getBigDecimal("sample_l"));
        po.setSampleA(rs.getBigDecimal("sample_a"));
        po.setSampleB(rs.getBigDecimal("sample_b"));
        po.setDeltaE(rs.getBigDecimal("delta_e"));
        po.setPassed(rs.getBoolean("passed"));
        Timestamp ts = rs.getTimestamp("created_at");
        po.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return po;
    };

    public void insert(ColorAssessmentPO po, LocalDateTime now) {
        jdbc.update("insert into color_assessment (request_id, sample_code, std_l, std_a, std_b, sample_l, "
                        + "sample_a, sample_b, delta_e, passed, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.getRequestId(), po.getSampleCode(), po.getStdL(), po.getStdA(), po.getStdB(),
                po.getSampleL(), po.getSampleA(), po.getSampleB(), po.getDeltaE(), po.isPassed(),
                Timestamp.valueOf(now));
    }
}
