package com.example.starter.cosmetic.child.persistence;

import com.example.starter.cosmetic.child.domain.AgeBand;
import com.example.starter.cosmetic.child.domain.ChildAgeRule;
import com.example.starter.cosmetic.child.domain.ChildEfficacy;
import com.example.starter.cosmetic.child.domain.ChildRestrictedLimit;
import com.example.starter.cosmetic.child.port.ChildCatalogPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的儿童分龄规则与儿童功效目录适配器。
 */
@Repository
public class JdbcChildCatalogPort implements ChildCatalogPort {

    private static final RowMapper<ChildEfficacy> EFFICACY_MAPPER = (rs, n) -> new ChildEfficacy(
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("supported") == 1);

    private static final RowMapper<ChildRestrictedLimit> LIMIT_MAPPER = (rs, n) -> new ChildRestrictedLimit(
            rs.getString("ingredient_code"),
            rs.getString("band_code"),
            rs.getBigDecimal("max_concentration_pct"));

    private final JdbcTemplate jdbc;

    public JdbcChildCatalogPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ChildAgeRule activeAgeRule() {
        List<AgeBand> bands = jdbc.query("""
                SELECT band_code, band_lower_months, band_upper_months
                FROM child_age_rule WHERE active = 1 ORDER BY band_lower_months
                """,
                (rs, n) -> new AgeBand(rs.getString("band_code"),
                        rs.getInt("band_lower_months"), rs.getInt("band_upper_months")));
        if (bands.isEmpty()) {
            throw new IllegalStateException("儿童分龄规则未初始化（无启用版本）");
        }
        String ruleVersion = jdbc.queryForObject(
                "SELECT DISTINCT rule_version FROM child_age_rule WHERE active = 1", String.class);
        return new ChildAgeRule(ruleVersion, bands);
    }

    @Override
    public Optional<ChildEfficacy> findEfficacy(String code) {
        return jdbc.query(
                "SELECT code, name, supported FROM child_efficacy_catalog WHERE code = ?",
                EFFICACY_MAPPER, code).stream().findFirst();
    }

    @Override
    public List<ChildEfficacy> listEfficacies() {
        return jdbc.query("SELECT code, name, supported FROM child_efficacy_catalog ORDER BY code",
                EFFICACY_MAPPER);
    }

    @Override
    public List<ChildRestrictedLimit> listRestrictedLimits(String ruleVersion) {
        return jdbc.query("""
                SELECT ingredient_code, band_code, max_concentration_pct
                FROM child_restricted_ingredient WHERE rule_version = ?
                ORDER BY ingredient_code, band_code
                """, LIMIT_MAPPER, ruleVersion);
    }
}
