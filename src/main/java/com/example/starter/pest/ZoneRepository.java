package com.example.starter.pest;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 库区与邻接关系数据访问。
 */
@Repository
public class ZoneRepository {

    private final JdbcClient jdbc;

    public ZoneRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 判断库区是否存在。
     */
    public boolean zoneExists(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM archive_zone WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * 查询某库区的全部相邻库区编码。
     */
    public List<String> adjacentZones(String code) {
        return jdbc.sql("SELECT adjacent_code FROM zone_adjacency WHERE zone_code = :code")
                .param("code", code)
                .query(String.class)
                .list();
    }
}
