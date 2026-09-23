package com.example.starter.spectrum.domain;

import java.util.List;

/**
 * 冲突解释结果中的单条冲突。
 *
 * @param type          EXCLUSIVE_CONFLICT=独占冲突；SHARED_EIRP_EXCEEDED=共享片段功率总和超限
 * @param cell          冲突所在区域单元
 * @param overlapStart  冲突片段时间上界（含），epoch 毫秒 UTC
 * @param overlapEnd    冲突片段时间下界（不含）
 * @param freqLowMhz    冲突片段频率下界 MHz（含）
 * @param freqHighMhz   冲突片段频率上界 MHz（不含）
 * @param totalEirpMw   片段内生效租约 EIRP 总和 mW（独占冲突时可能为 {@code null}）
 * @param eirpLimitMw   配置上限 mW（独占冲突时可能为 {@code null}）
 * @param contributors  片段内所有生效租约（租约 ID、版本、模式、功率）
 */
public record ConflictDetail(String type,
                             String cell,
                             long overlapStart,
                             long overlapEnd,
                             double freqLowMhz,
                             double freqHighMhz,
                             Double totalEirpMw,
                             Double eirpLimitMw,
                             List<Contributor> contributors) {

    /**
     * 片段内一个生效租约；候选请求自身尚未入库时 leaseId 为 {@code null}。
     *
     * @param leaseId 租约 ID；候选请求为 {@code null}
     * @param version 版本号；候选请求为其 expectedVersion（创建时为 0）
     * @param mode    模式
     * @param eirpMw  EIRP mW
     */
    public record Contributor(Long leaseId, int version, LeaseMode mode, double eirpMw) {
    }
}
