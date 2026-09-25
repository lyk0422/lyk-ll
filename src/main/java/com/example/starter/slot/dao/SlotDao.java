package com.example.starter.slot.dao;

import com.example.starter.slot.po.SeasonPo;
import com.example.starter.slot.po.SlotAllocationPo;
import com.example.starter.slot.po.SlotSeriesPo;
import com.example.starter.slot.po.SnapshotAllocationPo;

import java.time.Instant;
import java.util.List;

/**
 * 时刻协调数据访问边界（骨架，待实现）。
 */
public interface SlotDao {

    SeasonPo findSeason(String season);

    /**
     * 查询航季并加行锁（SELECT ... FOR UPDATE），用于串行化同航季的申请、退回与发布。
     */
    SeasonPo findSeasonForUpdate(String season);

    int insertSeasonIfAbsent(String season);

    boolean freezeSeasonIfOpen(String season, Instant now);

    SlotSeriesPo findSeriesByRequestId(String requestId);

    void insertSeries(SlotSeriesPo po);

    void insertAllocation(SlotAllocationPo po);

    List<SlotAllocationPo> findActiveAllocations(String season, String airport, String slotTime, List<Integer> weeks);

    List<SlotAllocationPo> findActiveAllocationsByRequestId(String requestId);

    int deactivateAllocations(String requestId);

    int markSeriesReleased(String requestId, long expectedVersion);

    List<SlotAllocationPo> findActiveAllocationsBySeason(String season);

    void insertSnapshot(String season, List<SlotAllocationPo> allocations);

    List<SnapshotAllocationPo> findSnapshot(String season);
}
