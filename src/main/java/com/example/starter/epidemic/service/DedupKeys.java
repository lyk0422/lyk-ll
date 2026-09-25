package com.example.starter.epidemic.service;

import java.time.LocalDate;

/**
 * 报告卡去重键规则：同一患者 + 同一病种 + 同一发病日期。
 * 有效卡的去重键固定为本格式；撤回卡改写为卡片编号作为不占槽的墓碑值。
 */
public final class DedupKeys {

    private DedupKeys() {
    }

    public static String of(String patientId, String diseaseCode, LocalDate onsetDate) {
        return patientId + "|" + diseaseCode + "|" + onsetDate;
    }
}
