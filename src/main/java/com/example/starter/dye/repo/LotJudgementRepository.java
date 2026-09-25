package com.example.starter.dye.repo;

import com.example.starter.dye.domain.LotJudgementPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 缸差判定台账仓储：按缸次/配方可复算历次判定与处置。
 */
@Repository
public class LotJudgementRepository {

    private final JdbcTemplate jdbc;

    public LotJudgementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LotJudgementPO> MAPPER = (rs, n) -> {
        LotJudgementPO po = new LotJudgementPO();
        po.setId(rs.getLong("id"));
        po.setRunId(rs.getLong("run_id"));
        po.setRunNo(rs.getString("run_no"));
        po.setRecipeCode(rs.getString("recipe_code"));
        po.setBaselineRunNo(rs.getString("baseline_run_no"));
        po.setDeltaEGap(rs.getBigDecimal("delta_e_gap"));
        po.setResult(rs.getString("result"));
        po.setRepairCount(rs.getInt("repair_count"));
        po.setRequestId(rs.getString("request_id"));
        Timestamp ts = rs.getTimestamp("created_at");
        po.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        return po;
    };

    public void insert(LotJudgementPO po, LocalDateTime now) {
        jdbc.update("insert into lot_judgement (run_id, run_no, recipe_code, baseline_run_no, delta_e_gap, "
                        + "result, repair_count, request_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.getRunId(), po.getRunNo(), po.getRecipeCode(), po.getBaselineRunNo(), po.getDeltaEGap(),
                po.getResult(), po.getRepairCount(), po.getRequestId(), Timestamp.valueOf(now));
    }

    public List<LotJudgementPO> findByRun(long runId) {
        return jdbc.query("select id, run_id, run_no, recipe_code, baseline_run_no, delta_e_gap, result, "
                + "repair_count, request_id, created_at from lot_judgement where run_id = ? order by id",
                MAPPER, runId);
    }

    public List<LotJudgementPO> findByRecipe(String recipeCode) {
        return jdbc.query("select id, run_id, run_no, recipe_code, baseline_run_no, delta_e_gap, result, "
                + "repair_count, request_id, created_at from lot_judgement where recipe_code = ? order by id",
                MAPPER, recipeCode);
    }
}
