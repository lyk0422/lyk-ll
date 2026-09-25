package com.example.starter.dye.repo;

import com.example.starter.dye.domain.Disposition;
import com.example.starter.dye.domain.RunStatus;
import com.example.starter.dye.domain.VatRunPO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 缸次仓储。{@link #findByNoForUpdate} 加行锁，保证称量扣库存与状态变更串行化。
 */
@Repository
public class VatRunRepository {

    private final JdbcTemplate jdbc;

    public VatRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<VatRunPO> RUN_MAPPER = (rs, n) -> {
        VatRunPO po = new VatRunPO();
        po.setId(rs.getLong("id"));
        po.setRunNo(rs.getString("run_no"));
        po.setVatId(rs.getLong("vat_id"));
        po.setRecipeId(rs.getLong("recipe_id"));
        po.setRecipeCode(rs.getString("recipe_code"));
        po.setRecipeVersion(rs.getInt("recipe_version"));
        po.setLiquorRatio(rs.getInt("liquor_ratio"));
        po.setFabricKg(rs.getBigDecimal("fabric_kg"));
        po.setMergedRunNo(rs.getString("merged_run_no"));
        po.setStatus(RunStatus.valueOf(rs.getString("status")));
        po.setRepairCount(rs.getInt("repair_count"));
        BigDecimal de = rs.getBigDecimal("out_delta_e");
        po.setOutDeltaE(rs.wasNull() ? null : de);
        String disp = rs.getString("final_disposition");
        po.setFinalDisposition(disp == null ? null : Disposition.valueOf(disp));
        Timestamp judged = rs.getTimestamp("judged_at");
        po.setJudgedAt(judged == null ? null : judged.toLocalDateTime());
        Timestamp created = rs.getTimestamp("created_at");
        po.setCreatedAt(created == null ? null : created.toLocalDateTime());
        return po;
    };

    public Optional<VatRunPO> findByNo(String runNo) {
        try {
            return Optional.of(jdbc.queryForObject(select(""), RUN_MAPPER, runNo));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 行锁读取，须在事务内调用。 */
    public Optional<VatRunPO> findByNoForUpdate(String runNo) {
        try {
            return Optional.of(jdbc.queryForObject(select(" for update"), RUN_MAPPER, runNo));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static String select(String suffix) {
        return "select id, run_no, vat_id, recipe_id, recipe_code, recipe_version, liquor_ratio, fabric_kg, "
                + "merged_run_no, status, repair_count, out_delta_e, final_disposition, judged_at, created_at "
                + "from vat_run where run_no = ?" + suffix;
    }

    public List<VatRunPO> findSameRecipe(String recipeCode) {
        return jdbc.query("select id, run_no, vat_id, recipe_id, recipe_code, recipe_version, liquor_ratio, "
                + "fabric_kg, merged_run_no, status, repair_count, out_delta_e, final_disposition, judged_at, "
                + "created_at from vat_run where recipe_code = ? order by id", RUN_MAPPER, recipeCode);
    }

    public long insert(VatRunPO po, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into vat_run (run_no, vat_id, recipe_id, recipe_code, recipe_version, liquor_ratio, "
                            + "fabric_kg, merged_run_no, status, repair_count, out_delta_e, final_disposition, "
                            + "judged_at, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.getRunNo());
            ps.setLong(2, po.getVatId());
            ps.setLong(3, po.getRecipeId());
            ps.setString(4, po.getRecipeCode());
            ps.setInt(5, po.getRecipeVersion());
            ps.setInt(6, po.getLiquorRatio());
            ps.setBigDecimal(7, po.getFabricKg());
            ps.setString(8, po.getMergedRunNo());
            ps.setString(9, po.getStatus().name());
            ps.setInt(10, po.getRepairCount());
            if (po.getOutDeltaE() == null) {
                ps.setNull(11, java.sql.Types.DECIMAL);
            } else {
                ps.setBigDecimal(11, po.getOutDeltaE());
            }
            ps.setString(12, po.getFinalDisposition() == null ? null : po.getFinalDisposition().name());
            ps.setTimestamp(13, po.getJudgedAt() == null ? null : Timestamp.valueOf(po.getJudgedAt()));
            ps.setTimestamp(14, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateStatus(long runId, RunStatus status) {
        jdbc.update("update vat_run set status = ? where id = ?", status.name(), runId);
    }

    public void updateMergedRunNo(long runId, String mergedRunNo) {
        jdbc.update("update vat_run set merged_run_no = ? where id = ?", mergedRunNo, runId);
    }

    public void updateOutDeltaE(long runId, BigDecimal deltaE) {
        jdbc.update("update vat_run set out_delta_e = ? where id = ?", deltaE, runId);
    }

    public void updateJudgement(long runId, RunStatus status, Disposition disposition,
                                int repairCount, LocalDateTime judgedAt) {
        jdbc.update("update vat_run set status = ?, final_disposition = ?, repair_count = ?, judged_at = ? "
                        + "where id = ?",
                status.name(), disposition == null ? null : disposition.name(), repairCount,
                judgedAt == null ? null : Timestamp.valueOf(judgedAt), runId);
    }
}
