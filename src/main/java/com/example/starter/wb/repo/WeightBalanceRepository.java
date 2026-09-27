package com.example.starter.wb.repo;

import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.AssemblyPlanRow;
import com.example.starter.wb.domain.Flight;
import com.example.starter.wb.domain.IdempotentRow;
import com.example.starter.wb.domain.LateEventRow;
import com.example.starter.wb.domain.LoadDetailRow;
import com.example.starter.wb.domain.LmcLogRow;
import com.example.starter.wb.domain.ManifestRow;
import com.example.starter.wb.domain.ReviewRow;
import com.example.starter.wb.domain.UldOccupancyRow;

import java.util.List;
import java.util.Optional;

/**
 * 载重平衡持久化边界。
 */
public interface WeightBalanceRepository {

    Aircraft findAircraftById(long id);

    Flight findFlightById(long id);

    /** 同一航班同时刻唯一有效版本：active_flag = flight_id。 */
    Optional<ManifestRow> findActiveByFlightId(long flightId);

    /** 行级加锁读取有效版本，用于序列化 LMC/重打。 */
    Optional<ManifestRow> findActiveByFlightIdForUpdate(long flightId);

    Optional<ManifestRow> findManifestById(long id);

    List<ManifestRow> findVersionsByFlightId(long flightId);

    List<LoadDetailRow> findDetails(long manifestId);

    List<ReviewRow> findReviews(long manifestId);

    List<LmcLogRow> findLmcLogs(long manifestId);

    List<LateEventRow> findLateEvents(long flightId);

    /** 插入舱单版本，返回自增主键；active_flag 唯一冲突由调用方转 409。 */
    long insertManifest(ManifestRow row);

    void insertDetails(long manifestId, List<LoadDetailRow> details);

    void deleteDetailsByManifest(long manifestId);

    /** 将旧有效版本作废（active_flag=NULL，状态 SUPERSEDED）。 */
    void supersede(long manifestId);

    /** 同事务内更新重量、重心、LMC 累计与状态（加油变更必带重心重算结果）。 */
    void updateComputed(ManifestRow row);

    void updateStatus(long manifestId, String status, java.time.LocalDateTime closedAt);

    long insertLmcLog(LmcLogRow row);

    void linkLmcNewVersion(long lmcLogId, Long newVersionId);

    /** 插入复核签字；(manifest,role) 唯一冲突由调用方处理。 */
    long insertReview(ReviewRow row);

    /** 删除某舱单版本全部复核签字：新装配图提交后旧双岗复核必须失效，不得沿用。 */
    void deleteReviewsByManifest(long manifestId);

    long insertLateEvent(LateEventRow row);

    Optional<IdempotentRow> findIdempotent(String requestId);

    void insertIdempotent(IdempotentRow row);

    // ------------------------------------------------------------- 集装器装配

    /** 当前有效装配图（active_flag=flight_id），行级加锁，用于序列化并发改图。 */
    Optional<AssemblyPlanRow> findActivePlanForUpdate(long flightId);

    Optional<AssemblyPlanRow> findActivePlan(long flightId);

    Optional<AssemblyPlanRow> findPlanById(long planId);

    /** 航班全部装配图版本（含已作废），按版本号升序，历史图只读。 */
    List<AssemblyPlanRow> findPlansByFlight(long flightId);

    List<UldOccupancyRow> findOccupanciesByPlan(long planId);

    /** 全局未关闭占用中该集装器编号的占用行（跨航班抢同器检测）。 */
    List<UldOccupancyRow> findOpenOccupanciesByUldNo(String uldNo);

    long insertPlan(AssemblyPlanRow row);

    void insertOccupancies(List<UldOccupancyRow> rows);

    /** 作废旧有效图（active_flag=NULL），历史行保留只读。 */
    void supersedePlan(long planId);

    /** 释放某装配图项下全部实物占用（open_flag=NULL），用于重新提交改图。 */
    void releaseOccupanciesByPlan(long planId, java.time.LocalDateTime releasedAt);

    /** 关闭放行时原子释放航班全部在装占用（open_flag=NULL）。 */
    void releaseOpenOccupanciesForFlight(long flightId, java.time.LocalDateTime releasedAt);

    /** 关闭放行时给有效图加盖释放时间，与占用释放在同一事务。 */
    void markPlanReleased(long planId, java.time.LocalDateTime releasedAt);
}
