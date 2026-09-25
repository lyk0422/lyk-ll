package com.example.starter.quarantine.repository;

import com.example.starter.quarantine.domain.AnimalType;
import com.example.starter.quarantine.domain.StockPo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 存栏仓储。出证核减与封锁作废返还均以条件 UPDATE 原子完成。
 */
@Repository
public class StockRepository {

    private static final RowMapper<StockPo> ROW_MAPPER = StockRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public StockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按养殖场+种类+耳标段查存栏批次。 */
    public StockPo findSlot(String farmId, AnimalType animalType, String earTagRange) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, farm_id, animal_type, ear_tag_range, total_count, available_count, version, created_at"
                            + " FROM farm_stock WHERE farm_id = ? AND animal_type = ? AND ear_tag_range = ?",
                    ROW_MAPPER, farmId, animalType.name(), earTagRange);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 查养殖场全部存栏批次。 */
    public List<StockPo> findByFarm(String farmId) {
        return jdbcTemplate.query(
                "SELECT id, farm_id, animal_type, ear_tag_range, total_count, available_count, version, created_at"
                        + " FROM farm_stock WHERE farm_id = ? ORDER BY id",
                ROW_MAPPER, farmId);
    }

    /**
     * 原子核减可售存栏；余额不足或批次不存在返回 false（调用方回滚事务）。
     */
    public boolean deductAvailable(String farmId, AnimalType animalType, String earTagRange, int quantity) {
        int rows = jdbcTemplate.update(
                "UPDATE farm_stock SET available_count = available_count - ?, version = version + 1"
                        + " WHERE farm_id = ? AND animal_type = ? AND ear_tag_range = ? AND available_count >= ?",
                quantity, farmId, animalType.name(), earTagRange, quantity);
        return rows == 1;
    }

    /** 封锁作废未启运证时返还存栏。 */
    public void restoreAvailable(String farmId, AnimalType animalType, String earTagRange, int quantity) {
        jdbcTemplate.update(
                "UPDATE farm_stock SET available_count = available_count + ?, version = version + 1"
                        + " WHERE farm_id = ? AND animal_type = ? AND ear_tag_range = ?",
                quantity, farmId, animalType.name(), earTagRange);
    }

    private static StockPo mapRow(ResultSet rs, int n) throws SQLException {
        StockPo po = new StockPo();
        po.setId(rs.getLong("id"));
        po.setFarmId(rs.getString("farm_id"));
        po.setAnimalType(AnimalType.valueOf(rs.getString("animal_type")));
        po.setEarTagRange(rs.getString("ear_tag_range"));
        po.setTotalCount(rs.getInt("total_count"));
        po.setAvailableCount(rs.getInt("available_count"));
        po.setVersion(rs.getInt("version"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            po.setCreatedAt(createdAt.toInstant());
        }
        return po;
    }
}
