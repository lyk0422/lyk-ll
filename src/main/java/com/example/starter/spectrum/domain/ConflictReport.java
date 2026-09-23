package com.example.starter.spectrum.domain;

import java.util.List;

/**
 * 候选租约与当前活动租约集合的冲突解释报告。
 *
 * @param compatible 是否完全相容（无独占冲突且无共享片段超限）
 * @param conflicts  全部冲突片段；相容时为空列表
 */
public record ConflictReport(boolean compatible, List<ConflictDetail> conflicts) {
}
