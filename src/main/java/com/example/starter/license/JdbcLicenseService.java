package com.example.starter.license;

import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LicenseUpsertRequest;
import com.example.starter.core.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合成危废经营许可库 JDBC 实现。
 */
@Service
public class JdbcLicenseService implements LicenseService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcLicenseService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional
    public LicenseResponse upsert(LicenseUpsertRequest request) {
        if (request.validTo().isBefore(request.validFrom())) {
            throw new IllegalArgumentException("许可截止日期不得早于生效日期");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        boolean revoked = Boolean.TRUE.equals(request.revoked());
        int updated = jdbcTemplate.update("""
                UPDATE hazardous_license
                   SET holder_name = ?, valid_from = ?, valid_to = ?, revoked = ?
                 WHERE license_no = ?
                """, request.holderName(), request.validFrom(), request.validTo(), revoked, request.licenseNo());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO hazardous_license (license_no, holder_name, valid_from, valid_to, revoked, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, request.licenseNo(), request.holderName(), request.validFrom(), request.validTo(),
                    revoked, Timestamp.valueOf(now));
        }
        return new LicenseResponse(request.licenseNo(), request.holderName(),
                request.validFrom(), request.validTo(), revoked, now);
    }

    @Override
    public void requireValid(String licenseNo, LocalDate date) {
        var rows = jdbcTemplate.queryForList("""
                SELECT valid_from, valid_to, revoked FROM hazardous_license WHERE license_no = ?
                """, licenseNo);
        if (rows.isEmpty()) {
            throw ApiException.rejected("LICENSE_NOT_FOUND",
                    "危废经营许可不存在，整单拒绝：licenseNo=" + licenseNo);
        }
        var row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("revoked"))) {
            throw ApiException.rejected("LICENSE_REVOKED",
                    "危废经营许可已被吊销，整单拒绝：licenseNo=" + licenseNo);
        }
        LocalDate validFrom = ((java.sql.Date) row.get("valid_from")).toLocalDate();
        LocalDate validTo = ((java.sql.Date) row.get("valid_to")).toLocalDate();
        if (date.isBefore(validFrom)) {
            throw ApiException.rejected("LICENSE_NOT_EFFECTIVE",
                    "危废经营许可尚未生效，整单拒绝：licenseNo=" + licenseNo);
        }
        if (date.isAfter(validTo)) {
            throw ApiException.rejected("LICENSE_EXPIRED",
                    "危废经营许可已于 " + validTo + " 过期，整单拒绝：licenseNo=" + licenseNo);
        }
    }
}
