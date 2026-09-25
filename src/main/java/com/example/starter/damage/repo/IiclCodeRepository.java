package com.example.starter.damage.repo;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.damage.repo.po.IiclCodePo;

/** 合成 IICL 代码表 + 修理项价目查询。 */
@Repository
public class IiclCodeRepository {

    private final JdbcTemplate jdbc;

    public IiclCodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量解析检验登记代码；返回记录数应与请求代码数一致，否则由服务层判 400。 */
    public List<IiclCodePo> findByCodes(List<String> codes) {
        String placeholders = String.join(",", codes.stream().map(c -> "?").toList());
        return jdbc.query("""
                SELECT c.code, c.description, c.default_repair_code,
                       r.name AS repair_name, r.unit, r.unit_cost
                FROM iicl_code c
                JOIN repair_item r ON r.repair_code = c.default_repair_code
                WHERE c.code IN (%s)
                """.formatted(placeholders),
                (rs, n) -> new IiclCodePo(
                        rs.getString("code"),
                        rs.getString("description"),
                        rs.getString("default_repair_code"),
                        rs.getString("repair_name"),
                        rs.getString("unit"),
                        rs.getBigDecimal("unit_cost")),
                codes.toArray());
    }
}
