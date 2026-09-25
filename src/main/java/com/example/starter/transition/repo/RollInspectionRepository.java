package com.example.starter.transition.repo;

import com.example.starter.transition.model.RollInspection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 纸卷检验记录仓储：首次检验、复议检验各至多一条（唯一约束）。
 */
@Repository
public class RollInspectionRepository {

    private static final RowMapper<RollInspection> MAPPER = (rs, n) -> {
        RollInspection i = new RollInspection();
        i.setId(rs.getLong("id"));
        i.setRollNo(rs.getString("roll_no"));
        i.setInspectionType(rs.getString("inspection_type"));
        i.setRequestId(rs.getString("request_id"));
        i.setAshValuePct(rs.getBigDecimal("ash_value_pct"));
        i.setMoisturePct(rs.getBigDecimal("moisture_pct"));
        i.setTensileIndex(rs.getBigDecimal("tensile_index"));
        i.setBasisWeightGM2(rs.getBigDecimal("basis_weight_g_m2"));
        i.setMeetsThreshold(rs.getInt("meets_threshold") != 0);
        i.setVerdict(rs.getString("verdict"));
        i.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return i;
    };

    private final JdbcTemplate jdbc;

    public RollInspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RollInspection inspection, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO roll_inspection
                            (roll_no, inspection_type, request_id, ash_value_pct, moisture_pct,
                             tensile_index, basis_weight_g_m2, meets_threshold, verdict, created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """,
                inspection.getRollNo(), inspection.getInspectionType(),
                inspection.getRequestId(), inspection.getAshValuePct(),
                inspection.getMoisturePct(), inspection.getTensileIndex(),
                inspection.getBasisWeightGM2(), inspection.isMeetsThreshold() ? 1 : 0,
                inspection.getVerdict(), Timestamp.valueOf(now));
    }

    public Optional<RollInspection> find(String rollNo, String inspectionType) {
        List<RollInspection> list = jdbc.query(
                "SELECT * FROM roll_inspection WHERE roll_no = ? AND inspection_type = ?",
                MAPPER, rollNo, inspectionType);
        return list.stream().findFirst();
    }
}
