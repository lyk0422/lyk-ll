package com.example.starter.feed.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 合成字典仓储：原料、药物添加剂（上限/休药天数）、允许动物种类、配伍禁忌。
 */
@Repository
public class DictRepository {

    private static final RowMapper<DrugDict> DRUG_MAPPER = (rs, n) -> new DrugDict(
            rs.getString("code"),
            rs.getString("name"),
            rs.getBigDecimal("max_ratio"),
            rs.getInt("withdrawal_days"),
            rs.getBoolean("active")
    );

    private final JdbcTemplate jdbc;

    public DictRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 药物字典行。 */
    public record DrugDict(String code, String name, BigDecimal maxRatio, int withdrawalDays, boolean active) {
    }

    /** 治疗性兽药目录行。 */
    public record VetDrugDict(String code, String name, int withdrawalDays, boolean active) {
    }

    private static final RowMapper<VetDrugDict> VET_DRUG_MAPPER = (rs, n) -> new VetDrugDict(
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("withdrawal_days"),
            rs.getBoolean("active")
    );

    /**
     * 按药品名称查治疗性兽药目录（直报受理口径）；目录外或停用视为不存在。
     */
    public Optional<VetDrugDict> findVetDrugByName(String name) {
        List<VetDrugDict> list = jdbc.query(
                "SELECT code, name, withdrawal_days, active FROM vet_drug_catalog WHERE name = ?",
                VET_DRUG_MAPPER, name);
        return list.stream().filter(VetDrugDict::active).findFirst();
    }

    public Optional<DrugDict> findDrug(String code) {
        List<DrugDict> list = jdbc.query(
                "SELECT code, name, max_ratio, withdrawal_days, active FROM drug_additive WHERE code = ?",
                DRUG_MAPPER, code);
        return list.stream().findFirst();
    }

    public List<DrugDict> findDrugs(List<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", codes.stream().map(c -> "?").toList());
        return jdbc.query(
                "SELECT code, name, max_ratio, withdrawal_days, active FROM drug_additive WHERE code IN ("
                        + placeholders + ")",
                DRUG_MAPPER, codes.toArray());
    }

    public List<String> findAllowedSpecies(String drugCode) {
        return jdbc.queryForList(
                "SELECT species FROM drug_allowed_species WHERE drug_code = ?",
                String.class, drugCode);
    }

    /**
     * 统计给定药物集合命中的配伍禁忌对数（禁忌表按编码字典序存无序对）。
     */
    public long countContraindicationPairs(List<String> drugCodes) {
        if (drugCodes.size() < 2) {
            return 0;
        }
        String placeholders = String.join(",", drugCodes.stream().map(c -> "?").toList());
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contraindication "
                        + "WHERE drug_a_code IN (" + placeholders + ") "
                        + "AND drug_b_code IN (" + placeholders + ")",
                Long.class, concatArgs(drugCodes));
        return count == null ? 0 : count;
    }

    public long countIngredients(List<String> codes) {
        if (codes.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", codes.stream().map(c -> "?").toList());
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ingredient WHERE code IN (" + placeholders + ")",
                Long.class, codes.toArray());
        return count == null ? 0 : count;
    }

    private Object[] concatArgs(List<String> codes) {
        Object[] args = new Object[codes.size() * 2];
        for (int i = 0; i < codes.size(); i++) {
            args[i] = codes.get(i);
            args[i + codes.size()] = codes.get(i);
        }
        return args;
    }

    /** 仅供其它映射器复用的时间转换工具。 */
    static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
