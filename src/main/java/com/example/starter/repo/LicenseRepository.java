package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.HazardousWasteLicense;

/**
 * 危废经营许可合成库访问。
 */
@Repository
public class LicenseRepository {

    private static final RowMapper<HazardousWasteLicense> MAPPER = (ResultSet rs, int n) -> new HazardousWasteLicense(
            rs.getLong("id"),
            rs.getString("license_no"),
            rs.getString("holder"),
            rs.getObject("valid_from", LocalDate.class),
            rs.getObject("valid_to", LocalDate.class));

    private final JdbcTemplate jdbc;

    public LicenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public HazardousWasteLicense findByLicenseNo(String licenseNo) {
        List<HazardousWasteLicense> list = jdbc.query(
                "SELECT id, license_no, holder, valid_from, valid_to FROM hazardous_waste_license WHERE license_no = ?",
                MAPPER, licenseNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public HazardousWasteLicense findById(Long id) {
        List<HazardousWasteLicense> list = jdbc.query(
                "SELECT id, license_no, holder, valid_from, valid_to FROM hazardous_waste_license WHERE id = ?",
                MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }
}
