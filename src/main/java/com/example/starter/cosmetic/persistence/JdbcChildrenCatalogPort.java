package com.example.starter.cosmetic.persistence;

import com.example.starter.cosmetic.domain.ChildrenAgeBandRule;
import com.example.starter.cosmetic.domain.ChildrenBandLimit;
import com.example.starter.cosmetic.domain.ChildrenEfficacy;
import com.example.starter.cosmetic.port.ChildrenCatalogPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的儿童分龄规则与儿童功效合成目录适配器。
 */
@Repository
public class JdbcChildrenCatalogPort implements ChildrenCatalogPort {

    private static final RowMapper<ChildrenAgeBandRule> BAND_MAPPER = (rs, n) -> new ChildrenAgeBandRule(
            rs.getString("band_code"),
            rs.getInt("lower_months"),
            rs.getInt("upper_months"),
            rs.getInt("rule_version"));

    private static final RowMapper<ChildrenBandLimit> LIMIT_MAPPER = (rs, n) -> new ChildrenBandLimit(
            rs.getString("band_code"),
            rs.getString("ingredient_code"),
            rs.getBigDecimal("max_concentration_pct"));

    private static final RowMapper<ChildrenEfficacy> EFFICACY_MAPPER = (rs, n) -> new ChildrenEfficacy(
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("supported") == 1);

    private final JdbcTemplate jdbc;

    public JdbcChildrenCatalogPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChildrenAgeBandRule> listBandRules() {
        return jdbc.query(
                "SELECT band_code, lower_months, upper_months, rule_version "
                        + "FROM children_age_band_rule ORDER BY lower_months ASC",
                BAND_MAPPER);
    }

    @Override
    public Optional<ChildrenAgeBandRule> findBandRule(String bandCode) {
        return jdbc.query(
                "SELECT band_code, lower_months, upper_months, rule_version "
                        + "FROM children_age_band_rule WHERE band_code = ?",
                BAND_MAPPER, bandCode).stream().findFirst();
    }

    @Override
    public int currentRuleVersion() {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(rule_version), 0) FROM children_age_band_rule", Integer.class);
        return v == null ? 0 : v;
    }

    @Override
    public Optional<ChildrenBandLimit> findBandLimit(String bandCode, String ingredientCode) {
        return jdbc.query(
                "SELECT band_code, ingredient_code, max_concentration_pct FROM children_band_limit "
                        + "WHERE band_code = ? AND ingredient_code = ?",
                LIMIT_MAPPER, bandCode, ingredientCode).stream().findFirst();
    }

    @Override
    public Optional<ChildrenEfficacy> findChildrenEfficacy(String code) {
        return jdbc.query(
                "SELECT code, name, supported FROM children_efficacy_catalog WHERE code = ?",
                EFFICACY_MAPPER, code).stream().findFirst();
    }
}
