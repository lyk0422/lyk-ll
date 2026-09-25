package com.example.starter.flood.repo;

import com.example.starter.flood.service.StorageCurveCalculator.CurvePoint;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 尾矿库档案与库容曲线查询。
 */
@Repository
public class PondRepository {

    private final JdbcTemplate jdbcTemplate;

    public PondRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<PondPO> POND_MAPPER = new RowMapper<>() {
        @Override
        public PondPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            PondPO po = new PondPO();
            po.id = rs.getLong("id");
            po.pondCode = rs.getString("pond_code");
            po.pondName = rs.getString("pond_name");
            po.downstreamSafeRelease = rs.getBigDecimal("downstream_safe_release");
            po.dryBeachLimit = rs.getBigDecimal("dry_beach_limit");
            po.phreaticLimit = rs.getBigDecimal("phreatic_limit");
            return po;
        }
    };

    public PondPO findByCode(String pondCode) {
        List<PondPO> list = jdbcTemplate.query(
                "SELECT * FROM pond WHERE pond_code = ?", POND_MAPPER, pondCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public PondPO findById(Long id) {
        List<PondPO> list = jdbcTemplate.query(
                "SELECT * FROM pond WHERE id = ?", POND_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public PondPO requireByCode(String pondCode) {
        PondPO pond = findByCode(pondCode);
        if (pond == null) {
            throw com.example.starter.flood.exception.FloodControlException.notFound(
                    "尾矿库不存在: " + pondCode);
        }
        return pond;
    }

    /**
     * 读取该库全部库容曲线点。
     */
    public List<CurvePoint> findCurvePoints(Long pondId) {
        return jdbcTemplate.query(
                "SELECT water_level, storage FROM storage_curve_point WHERE pond_id = ?",
                (rs, rowNum) -> new CurvePoint(
                        rs.getBigDecimal("water_level"),
                        rs.getBigDecimal("storage")),
                pondId);
    }

    /**
     * 曲线最高水位对应蓄水量（调洪高限库容）。
     */
    public BigDecimal maxStorage(Long pondId) {
        return jdbcTemplate.queryForObject(
                "SELECT MAX(storage) FROM storage_curve_point WHERE pond_id = ?",
                BigDecimal.class, pondId);
    }
}
