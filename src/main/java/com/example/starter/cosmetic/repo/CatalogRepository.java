package com.example.starter.cosmetic.repo;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.starter.cosmetic.domain.EfficacyEntry;
import com.example.starter.cosmetic.domain.RestrictedEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 功效目录与限用成分目录的数据访问（合成参考数据）。
 */
@Repository
public class CatalogRepository {

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取全部功效目录，键为功效编码。
     */
    public Map<String, EfficacyEntry> findAllEfficacy() {
        return jdbc.sql("SELECT code, name, special, supported FROM efficacy_catalog ORDER BY code")
                .query((rs, rowNum) -> new EfficacyEntry(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getBoolean("special"),
                        rs.getBoolean("supported")))
                .list()
                .stream()
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.code(), entry),
                        Map::putAll);
    }

    /**
     * 读取全部限用成分目录，键为成分编码。
     */
    public Map<String, RestrictedEntry> findAllRestricted() {
        return jdbc.sql("SELECT code, name, max_concentration FROM restricted_ingredient ORDER BY code")
                .query((rs, rowNum) -> new RestrictedEntry(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getBigDecimal("max_concentration")))
                .list()
                .stream()
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.code(), entry),
                        Map::putAll);
    }

    /**
     * 功效目录行数，用于种子数据幂等初始化。
     */
    public int countEfficacy() {
        return jdbc.sql("SELECT COUNT(*) FROM efficacy_catalog").query(Integer.class).single();
    }

    /**
     * 限用成分目录行数，用于种子数据幂等初始化。
     */
    public int countRestricted() {
        return jdbc.sql("SELECT COUNT(*) FROM restricted_ingredient").query(Integer.class).single();
    }

    /**
     * 写入功效目录条目（种子数据）。
     */
    public void insertEfficacy(EfficacyEntry entry) {
        jdbc.sql("INSERT INTO efficacy_catalog (code, name, special, supported) VALUES (:code, :name, :special, :supported)")
                .param("code", entry.code())
                .param("name", entry.name())
                .param("special", entry.special())
                .param("supported", entry.supported())
                .update();
    }

    /**
     * 写入限用成分条目（种子数据）。
     */
    public void insertRestricted(RestrictedEntry entry) {
        jdbc.sql("INSERT INTO restricted_ingredient (code, name, max_concentration) VALUES (:code, :name, :max)")
                .param("code", entry.code())
                .param("name", entry.name())
                .param("max", entry.maxConcentration())
                .update();
    }
}
