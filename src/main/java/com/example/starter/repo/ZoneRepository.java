package com.example.starter.repo;

import com.example.starter.domain.OverlapZone;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 重叠区数据访问。授权申请、释放与位姿越限判定均以行锁串行化，保证并发下结论唯一。
 */
@Repository
public class ZoneRepository {

    private final JdbcTemplate jdbc;

    public ZoneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OverlapZone> findById(String id) {
        return jdbc.query("SELECT id, name, center_x_m, center_y_m, radius_m FROM overlap_zone WHERE id = ?",
                (rs, n) -> map(rs.getString("id"), rs.getString("name"),
                        rs.getDouble("center_x_m"), rs.getDouble("center_y_m"), rs.getDouble("radius_m")),
                id).stream().findFirst();
    }

    public List<OverlapZone> findAll() {
        return jdbc.query("SELECT id, name, center_x_m, center_y_m, radius_m FROM overlap_zone ORDER BY id",
                (rs, n) -> map(rs.getString("id"), rs.getString("name"),
                        rs.getDouble("center_x_m"), rs.getDouble("center_y_m"), rs.getDouble("radius_m")));
    }

    /**
     * 在当前事务内对重叠区行加写锁（SELECT ... FOR UPDATE），用于串行化授权与越限判定。
     */
    public void lockById(String id) {
        jdbc.query("SELECT id FROM overlap_zone WHERE id = ? FOR UPDATE", (rs, n) -> rs.getString("id"), id);
    }

    private OverlapZone map(String id, String name, double cx, double cy, double r) {
        return new OverlapZone(id, name, cx, cy, r);
    }
}
