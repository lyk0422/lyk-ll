package com.example.starter.feed.repo;

import com.example.starter.feed.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 配方版本及组分仓储。
 */
@Repository
public class FormulaRepository {

    private final JdbcTemplate jdbc;

    public FormulaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** feed_formula 表行。 */
    public record FormulaRow(
            long id,
            String formulaCode,
            int version,
            String name,
            String targetSpecies,
            boolean containsDrug,
            String status,
            String rejectReason,
            String submitRequestId,
            String fingerprint,
            Instant approvedAtUtc,
            Instant firstProducedAtUtc,
            Instant createdAtUtc
    ) {
    }

    /** 配方组分（原料/药物）行。 */
    public record ComponentRow(String code, BigDecimal ratio) {
    }

    private static final RowMapper<FormulaRow> FORMULA_MAPPER = (rs, n) -> new FormulaRow(
            rs.getLong("id"),
            rs.getString("formula_code"),
            rs.getInt("version"),
            rs.getString("name"),
            rs.getString("target_species"),
            rs.getBoolean("contains_drug"),
            rs.getString("status"),
            rs.getString("reject_reason"),
            rs.getString("submit_request_id"),
            rs.getString("fingerprint"),
            DictRepository.toInstant(rs.getTimestamp("approved_at_utc")),
            DictRepository.toInstant(rs.getTimestamp("first_produced_at_utc")),
            DictRepository.toInstant(rs.getTimestamp("created_at_utc"))
    );

    private static final RowMapper<ComponentRow> COMPONENT_MAPPER =
            (rs, n) -> new ComponentRow(rs.getString(1), rs.getBigDecimal(2));

    public long insertFormula(Instant now, String formulaCode, int version, String name, String targetSpecies,
                              boolean containsDrug, String status, String rejectReason,
                              String submitRequestId, String fingerprint) {
        jdbc.update("INSERT INTO feed_formula (formula_code, version, name, target_species, contains_drug, "
                        + "status, reject_reason, submit_request_id, fingerprint, created_at_utc) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                formulaCode, version, name, targetSpecies, containsDrug, status, rejectReason,
                submitRequestId, fingerprint, Timestamp.from(now));
        return jdbc.queryForObject(
                "SELECT id FROM feed_formula WHERE formula_code = ? AND version = ?",
                Long.class, formulaCode, version);
    }

    public void insertIngredient(long formulaId, String code, BigDecimal ratio) {
        jdbc.update("INSERT INTO formula_ingredient (formula_id, ingredient_code, ratio) VALUES (?,?,?)",
                formulaId, code, ratio);
    }

    public void insertDrug(long formulaId, String code, BigDecimal ratio) {
        jdbc.update("INSERT INTO formula_drug (formula_id, drug_code, ratio) VALUES (?,?,?)",
                formulaId, code, ratio);
    }

    public Optional<FormulaRow> find(String formulaCode, int version) {
        return jdbc.query("SELECT * FROM feed_formula WHERE formula_code = ? AND version = ?",
                        FORMULA_MAPPER, formulaCode, version)
                .stream().findFirst();
    }

    public FormulaRow get(String formulaCode, int version) {
        return find(formulaCode, version)
                .orElseThrow(() -> ApiException.notFound(
                        "配方版本不存在: " + formulaCode + " v" + version));
    }

    public List<FormulaRow> listVersions(String formulaCode) {
        return jdbc.query("SELECT * FROM feed_formula WHERE formula_code = ? ORDER BY version",
                FORMULA_MAPPER, formulaCode);
    }

    public Optional<Integer> maxVersion(String formulaCode) {
        Integer v = jdbc.queryForObject(
                "SELECT MAX(version) FROM feed_formula WHERE formula_code = ?",
                Integer.class, formulaCode);
        return Optional.ofNullable(v);
    }

    public List<ComponentRow> findIngredients(long formulaId) {
        return jdbc.query("SELECT ingredient_code, ratio FROM formula_ingredient WHERE formula_id = ? ORDER BY id",
                COMPONENT_MAPPER, formulaId);
    }

    public List<ComponentRow> findDrugs(long formulaId) {
        return jdbc.query("SELECT drug_code, ratio FROM formula_drug WHERE formula_id = ? ORDER BY id",
                COMPONENT_MAPPER, formulaId);
    }

    public int markApproved(long formulaId, Instant approvedAtUtc) {
        return jdbc.update(
                "UPDATE feed_formula SET status = 'APPROVED', reject_reason = NULL, approved_at_utc = ? "
                        + "WHERE id = ? AND status = 'SUBMITTED'",
                Timestamp.from(approvedAtUtc), formulaId);
    }

    public int markFirstProduced(long formulaId, Instant producedAtUtc) {
        return jdbc.update(
                "UPDATE feed_formula SET status = 'IN_PRODUCTION', first_produced_at_utc = ? "
                        + "WHERE id = ? AND status = 'APPROVED'",
                Timestamp.from(producedAtUtc), formulaId);
    }
}
