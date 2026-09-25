package com.example.starter.fishery.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 渔船、鱼种等合成基础数据访问。
 */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编号查询渔船，不存在返回 null。 */
    public VesselRow findVessel(String code) {
        List<VesselRow> rows = jdbc.query(
                "SELECT code, name, created_at FROM vessel WHERE code = ?",
                (rs, n) -> new VesselRow(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant()),
                code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按编号查询鱼种，不存在返回 null。 */
    public SpeciesRow findSpecies(String code) {
        List<SpeciesRow> rows = jdbc.query(
                "SELECT code, name, protected_species FROM species WHERE code = ?",
                (rs, n) -> new SpeciesRow(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getBoolean("protected_species")),
                code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 渔船建档（合成数据）。 */
    public void insertVessel(String code, String name, java.time.Instant createdAt) {
        jdbc.update("INSERT INTO vessel(code, name, created_at) VALUES (?, ?, ?)",
                code, name, Timestamp.from(createdAt));
    }

    /** 鱼种建档（合成数据）。 */
    public void insertSpecies(String code, String name, boolean protectedSpecies) {
        jdbc.update("INSERT INTO species(code, name, protected_species) VALUES (?, ?, ?)",
                code, name, protectedSpecies);
    }
}
