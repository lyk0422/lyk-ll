package com.example.starter.cosmetic.persistence;

import com.example.starter.cosmetic.domain.AgeBandRule;
import com.example.starter.cosmetic.domain.EfficacyCatalog;
import com.example.starter.cosmetic.domain.RestrictedIngredient;
import com.example.starter.cosmetic.port.CatalogPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的合成目录适配器（功效目录、限用成分限值、儿童分龄规则）。
 */
@Repository
public class JdbcCatalogPort implements CatalogPort {

    private static final RowMapper<EfficacyCatalog> EFFICACY_MAPPER = (rs, n) -> new EfficacyCatalog(
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("special") == 1,
            rs.getInt("supported") == 1,
            rs.getInt("child_allowed") == 1);

    private static final RowMapper<RestrictedIngredient> RESTRICTED_MAPPER = (rs, n) -> new RestrictedIngredient(
            rs.getString("ingredient_code"),
            rs.getString("name"),
            rs.getBigDecimal("max_concentration_pct"),
            rs.getBigDecimal("child_max_band_0_36_pct"),
            rs.getBigDecimal("child_max_band_37_144_pct"));

    private static final RowMapper<AgeBandRule> AGE_BAND_MAPPER = (rs, n) -> new AgeBandRule(
            rs.getString("rule_version"),
            rs.getString("band_code"),
            rs.getInt("lower_months"),
            rs.getInt("upper_months"));

    private final JdbcTemplate jdbc;

    public JdbcCatalogPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EfficacyCatalog> findEfficacy(String code) {
        return jdbc.query(
                "SELECT code, name, special, supported, child_allowed FROM efficacy_catalog WHERE code = ?",
                EFFICACY_MAPPER, code).stream().findFirst();
    }

    @Override
    public List<EfficacyCatalog> listEfficacies() {
        return jdbc.query(
                "SELECT code, name, special, supported, child_allowed FROM efficacy_catalog ORDER BY code",
                EFFICACY_MAPPER);
    }

    @Override
    public Optional<RestrictedIngredient> findRestricted(String ingredientCode) {
        return jdbc.query(
                "SELECT ingredient_code, name, max_concentration_pct, "
                        + "child_max_band_0_36_pct, child_max_band_37_144_pct "
                        + "FROM restricted_ingredient WHERE ingredient_code = ?",
                RESTRICTED_MAPPER, ingredientCode).stream().findFirst();
    }

    @Override
    public String currentAgeRuleVersion() {
        String version = jdbc.queryForObject(
                "SELECT rule_version FROM age_rule_version WHERE id = 1", String.class);
        if (version == null) {
            throw new IllegalStateException("儿童分龄规则版本未初始化");
        }
        return version;
    }

    @Override
    public List<AgeBandRule> listAgeBands(String ruleVersion) {
        return jdbc.query(
                "SELECT rule_version, band_code, lower_months, upper_months "
                        + "FROM age_band_rule WHERE rule_version = ? ORDER BY lower_months ASC",
                AGE_BAND_MAPPER, ruleVersion);
    }
}
