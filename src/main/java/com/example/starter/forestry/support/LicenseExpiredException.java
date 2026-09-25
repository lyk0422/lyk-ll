package com.example.starter.forestry.support;

/**
 * 许可证逾期异常：抛出前已完成作废与限额释放，事务须提交（dontRollbackOn）。
 */
public class LicenseExpiredException extends ForestryException {

    public LicenseExpiredException(long licenseId) {
        super(409, "LICENSE_EXPIRED", "许可证 " + licenseId + " 已逾期作废，限额已释放");
    }
}
