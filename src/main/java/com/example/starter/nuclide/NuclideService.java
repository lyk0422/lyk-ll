package com.example.starter.nuclide;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import com.example.starter.error.ApiExceptionHandler.NotFoundException;

/**
 * 合成核素登记与查询；半衰期参数的唯一来源。
 */
@Service
public class NuclideService {

    private final JdbcClient jdbc;

    public NuclideService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 核素视图。
     *
     * @param id               主键
     * @param name             核素名称（合成数据）
     * @param halfLifeSeconds  半衰期，单位秒
     */
    public record NuclideView(long id, String name, long halfLifeSeconds) {
    }

    /**
     * 登记合成核素。
     */
    public NuclideView register(String name, long halfLifeSeconds) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO nuclide (name, half_life_seconds) VALUES (?, ?)")
                .params(name, halfLifeSeconds)
                .update(keyHolder);
        long id = keyHolder.getKey().longValue();
        return new NuclideView(id, name, halfLifeSeconds);
    }

    /**
     * 按主键查询，不存在抛 404。
     */
    public NuclideView require(long id) {
        return jdbc.sql("SELECT id, name, half_life_seconds FROM nuclide WHERE id = ?")
                .param(id)
                .query((rs, rn) -> new NuclideView(rs.getLong("id"), rs.getString("name"),
                        rs.getLong("half_life_seconds")))
                .optional()
                .orElseThrow(() -> new NotFoundException("核素不存在: " + id));
    }

    /**
     * 全量查询。
     */
    public List<NuclideView> list() {
        return jdbc.sql("SELECT id, name, half_life_seconds FROM nuclide ORDER BY id")
                .query((rs, rn) -> new NuclideView(rs.getLong("id"), rs.getString("name"),
                        rs.getLong("half_life_seconds")))
                .list();
    }
}
