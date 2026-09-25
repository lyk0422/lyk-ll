package com.example.starter.cutover.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 合成主数据访问：设备、业务系统及其关联关系。
 */
@Repository
public class MasterDataRepository {

    private final JdbcTemplate jdbcTemplate;

    public MasterDataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean deviceExists(String deviceCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device WHERE code = ?", Integer.class, deviceCode);
        return count != null && count > 0;
    }

    /**
     * 锁定设备行，串行化同设备的审批，防止并发批准产生窗口重叠。
     */
    public void lockDevice(String deviceCode) {
        jdbcTemplate.queryForList(
                "SELECT id FROM device WHERE code = ? FOR UPDATE", deviceCode);
    }

    /**
     * 查询设备关联的全部业务系统编码（影响分析必须列全）。
     */
    public List<String> findBusinessCodesByDevice(String deviceCode) {
        return jdbcTemplate.queryForList(
                "SELECT business_code FROM device_business WHERE device_code = ? ORDER BY business_code",
                String.class, deviceCode);
    }

    public List<String> findAllDevices() {
        return jdbcTemplate.queryForList("SELECT code FROM device ORDER BY code", String.class);
    }
}
