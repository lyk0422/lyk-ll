package com.example.starter.dye.repo;

import com.example.starter.dye.domain.WeighRecordPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 称量链仓储：按缸次可复算每项计划/实际克重与偏差。
 */
@Repository
public class WeighRecordRepository {

    private final JdbcTemplate jdbc;

    public WeighRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WeighRecordPO> MAPPER = (rs, n) -> {
        WeighRecordPO po = new WeighRecordPO();
        po.setId(rs.getLong("id"));
        po.setRunId(rs.getLong("run_id"));
        po.setRequestId(rs.getString("request_id"));
        po.setDyestuffCode(rs.getString("dyestuff_code"));
        po.setPlannedGrams(rs.getBigDecimal("planned_grams"));
        po.setActualGrams(rs.getBigDecimal("actual_grams"));
        po.setDeviationRatio(rs.getBigDecimal("deviation_ratio"));
        po.setAccepted(rs.getBoolean("accepted"));
        Timestamp ts = rs.getTimestamp("created_at");
        po.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return po;
    };

    public void insert(WeighRecordPO po, LocalDateTime now) {
        jdbc.update("insert into weigh_record (run_id, request_id, dyestuff_code, planned_grams, actual_grams, "
                        + "deviation_ratio, accepted, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                po.getRunId(), po.getRequestId(), po.getDyestuffCode(), po.getPlannedGrams(),
                po.getActualGrams(), po.getDeviationRatio(), po.isAccepted(), Timestamp.valueOf(now));
    }

    public List<WeighRecordPO> findByRun(long runId) {
        return jdbc.query("select id, run_id, request_id, dyestuff_code, planned_grams, actual_grams, "
                + "deviation_ratio, accepted, created_at from weigh_record where run_id = ? order by id",
                MAPPER, runId);
    }
}
