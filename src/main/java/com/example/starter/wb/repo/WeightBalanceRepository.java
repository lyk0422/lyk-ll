package com.example.starter.wb.repo;

import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.AssemblyItemRow;
import com.example.starter.wb.domain.AssemblyPlanRow;
import com.example.starter.wb.domain.CompartmentRow;
import com.example.starter.wb.domain.Flight;
import com.example.starter.wb.domain.IdempotentRow;
import com.example.starter.wb.domain.LateEventRow;
import com.example.starter.wb.domain.LoadDetailRow;
import com.example.starter.wb.domain.LmcLogRow;
import com.example.starter.wb.domain.ManifestRow;
import com.example.starter.wb.domain.ReviewRow;
import com.example.starter.wb.domain.UldOccupationRow;

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

    long insertLateEvent(LateEventRow row);

    Optional<IdempotentRow> findIdempotent(String requestId);

    void insertIdempotent(IdempotentRow row);

    // ------------------------------------------------------------ 集装器装配

    List<CompartmentRow> findCompartments(long aircraftId);

    /** 航班当前有效装配图（active_flag = flight_id）。 */
    Optional<AssemblyPlanRow> findActivePlanByFlightId(long flightId);

    /** 行级加锁读取有效装配图，用于序列化装配提交与关闭释放。 */
    Optional<AssemblyPlanRow> findActivePlanByFlightIdForUpdate(long flightId);

    List<AssemblyPlanRow> findPlansByFlightId(long flightId);

    List<AssemblyItemRow> findPlanItems(long planId);

    /** 插入装配图新版本；(flight_id,plan_version) 与 (flight_id,active_flag) 唯一冲突由调用方处理。 */
    long insertPlan(AssemblyPlanRow row);

    void insertPlanItems(long planId, List<AssemblyItemRow> items);

    /** 旧装配图作废（active_flag=NULL，状态 SUPERSEDED），仅只读追溯。 */
    void supersedePlan(long planId);

    /** 关闭放行后标记装配图已释放（active_flag=NULL，状态 RELEASED）。 */
    void releasePlan(long planId);

    /** 使舱单既有双岗复核全部失效（装配变更后不得沿用旧复核）。 */
    void deleteReviewsByManifest(long manifestId);

    Optional<UldOccupationRow> findOccupation(String uldNumber);

    List<UldOccupationRow> findOccupationsByFlightId(long flightId);

    /** 占用集装器；编号主键冲突表示被其他未关闭航班占用，由调用方转 409。 */
    void insertOccupation(UldOccupationRow row);

    /** 关闭放行或换装新图时原子释放本航班全部实物占用。 */
    void deleteOccupationsByFlightId(long flightId);
}
