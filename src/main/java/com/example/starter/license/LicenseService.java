package com.example.starter.license;

import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LicenseUpsertRequest;
import java.time.LocalDate;

/**
 * 合成危废经营许可库：方案须绑定有效许可，过期（审核时刻晚于 validTo）直接拒绝。
 */
public interface LicenseService {

    LicenseResponse upsert(LicenseUpsertRequest request);

    /**
     * 校验许可在指定日期是否有效：存在、未吊销、生效日期 ≤ date ≤ 截止日期。
     */
    void requireValid(String licenseNo, LocalDate date);
}
