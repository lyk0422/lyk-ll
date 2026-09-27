package com.example.starter.cosmetic.child.persistence;

import com.example.starter.cosmetic.child.domain.AgeTier;
import com.example.starter.cosmetic.child.domain.ChildEfficacy;
import com.example.starter.cosmetic.child.domain.ChildIngredientLimit;
import com.example.starter.cosmetic.child.port.ChildCatalogPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的儿童分龄合成规则适配器。
 */
@Repository
public class JdbcChildCatalogPort implements ChildCatalogPort {

    private static final RowMapper<ChildEfficacy> EFFICACY_MAPPER = (rs, n) -> new ChildEfficacy(
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("supported") == 1);

    private final JdbcTemplate jdbc;

    public JdbcChildCatalogPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String currentRuleVersion() {
        // 合成初始化仅有一个版本；取字典序最大（未来新增版本时自动取最新）
        return jdbc.queryForObject(
                "SELECT MAX(rule_version) FROM child_age_rule", String.class);
    }

    @Override
    public List<AgeTier> tiers(String ruleVersion) {
        return jdbc.query(
                "SELECT tier_code, age_from_months, age_to_months FROM child_age_rule "
                        + "WHERE rule_version = ? ORDER BY age_from_months ASC",
                (rs, n) -> AgeTier.valueOf(rs.getString("tier_code")),
                ruleVersion);
    }

    @Override
    public Optional<ChildEfficacy> findEfficacy(String code) {
        return jdbc.query(
                "SELECT code, name, supported FROM child_efficacy_catalog WHERE code = ?",
                EFFICACY_MAPPER, code).stream().findFirst();
    }

    @Override
    public Optional<BigDecimal> findLimit(String ruleVersion, AgeTier tier, String ingredientCode) {
        return jdbc.query(
                "SELECT max_concentration_pct FROM child_ingredient_limit "
                        + "WHERE rule_version = ? AND tier_code = ? AND ingredient_code = ?",
                (rs, n) -> rs.getBigDecimal("max_concentration_pct"),
                ruleVersion, tier.name(), ingredientCode).stream().findFirst();
    }

    @Override
    public List<ChildIngredientLimit> listLimits(String ruleVersion) {
        return jdbc.query(
                "SELECT rule_version, tier_code, ingredient_code, name, max_concentration_pct "
                        + "FROM child_ingredient_limit WHERE rule_version = ? "
                        + "ORDER BY tier_code ASC, ingredient_code ASC",
                (rs, n) -> new ChildIngredientLimit(
                        rs.getString("rule_version"),
                        AgeTier.valueOf(rs.getString("tier_code")),
                        rs.getString("ingredient_code"),
                        rs.getString("name"),
                        rs.getBigDecimal("max_concentration_pct")),
                ruleVersion);
    }
}
