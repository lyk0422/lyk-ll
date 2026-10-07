package com.example.starter.runway;

import org.springframework.stereotype.Component;

/**
 * 施工闭锁守卫：恢复开放与例行开始等“新使用”入口统一在此检查活动施工闭锁。
 * 未受控跑道（未登记）原行为不变，直接放行。
 */
@Component
public class ConstructionLockGuard {

    private final ControlledRunwayRepository controlledRunwayRepository;
    private final ConstructionSegmentRepository segmentRepository;

    public ConstructionLockGuard(ControlledRunwayRepository controlledRunwayRepository,
                                 ConstructionSegmentRepository segmentRepository) {
        this.controlledRunwayRepository = controlledRunwayRepository;
        this.segmentRepository = segmentRepository;
    }

    /** 该跑道当前是否存在活动施工闭锁（已生效单的 LOCKED 段）；未受控跑道恒为 false。 */
    public boolean hasActiveLock(String runwayId) {
        if (!controlledRunwayRepository.existsById(runwayId)) {
            return false;
        }
        return segmentRepository.countLockedByRunway(runwayId) > 0;
    }

    /** 存在活动施工闭锁时拒绝（422）；未受控跑道原行为不变。 */
    public void assertNoActiveLock(String runwayId) {
        if (hasActiveLock(runwayId)) {
            throw ApiException.unprocessable("CONSTRUCTION_LOCK_ACTIVE",
                    "跑道存在活动施工闭锁，须全部施工段验收完成后方可新使用: " + runwayId);
        }
    }
}
