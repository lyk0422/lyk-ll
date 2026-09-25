package com.example.starter.epidemic.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 病种与机构目录只读仓储。
 */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DiseasePo> DISEASE_MAPPER = (rs, n) -> {
        DiseasePo po = new DiseasePo();
        po.code = rs.getString("code");
        po.name = rs.getString("name");
        po.category = rs.getString("category");
        po.enabled = rs.getInt("enabled") == 1;
        return po;
    };

    public Optional<DiseasePo> findDisease(String code) {
        return jdbc.query("SELECT code, name, category, enabled FROM disease_catalog WHERE code = ?",
                ps -> ps.setString(1, code),
                rs -> rs.next() ? Optional.of(DISEASE_MAPPER.mapRow(rs, 1)) : Optional.empty());
    }

    public boolean facilityEnabled(String code) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM medical_facility WHERE code = ? AND enabled = 1",
                Integer.class, code);
        return cnt != null && cnt > 0;
    }
}
