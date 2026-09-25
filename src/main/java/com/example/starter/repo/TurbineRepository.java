package com.example.starter.repo;

import java.util.List;
import java.util.Optional;

import com.example.starter.domain.TurbineRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 机组数据访问。
 */
@Repository
public class TurbineRepository {

    private final JdbcTemplate jdbc;

    public TurbineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按风场查询机组，按 id 升序保证分摊顺序稳定。
     */
    public List<TurbineRow> findByFarmId(long farmId) {
        return jdbc.query(
                "SELECT id, farm_id, code, capacity_kw, available_kw FROM turbine WHERE farm_id = ? ORDER BY id",
                (rs, i) -> new TurbineRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5)),
                farmId);
    }

    public Optional<TurbineRow> findById(long id) {
        List<TurbineRow> rows = jdbc.query(
                "SELECT id, farm_id, code, capacity_kw, available_kw FROM turbine WHERE id = ?",
                (rs, i) -> new TurbineRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getInt(4), rs.getInt(5)),
                id);
        return rows.stream().findFirst();
    }

    /**
     * 用最新出力读数刷新机组可用出力。
     */
    public void updateAvailableKw(long id, int availableKw) {
        jdbc.update("UPDATE turbine SET available_kw = ? WHERE id = ?", availableKw, id);
    }
}
