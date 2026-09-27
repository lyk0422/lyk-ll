package com.example.starter.wb.repo;

import com.example.starter.wb.domain.Aircraft;
import com.example.starter.wb.domain.CargoItemRow;
import com.example.starter.wb.domain.CargoOccupancyRow;
import com.example.starter.wb.domain.CargoPlanRow;
import com.example.starter.wb.domain.CompartmentRow;
import com.example.starter.wb.domain.Flight;
import com.example.starter.wb.domain.IdempotentRow;
import com.example.starter.wb.domain.LateEventRow;
import com.example.starter.wb.domain.LoadDetailRow;
import com.example.starter.wb.domain.LmcLogRow;
import com.example.starter.wb.domain.ManifestRow;
import com.example.starter.wb.domain.ReviewRow;

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

    // ----------------------------------------------------- 集装器装配图

    /** 飞机的集装器舱位主数据（整数毫米力臂、整数公斤限额、允许类型）。 */
    List<CompartmentRow> findCompartments(long aircraftId);

    /** 航班下全部装配图（含各舱单版本），按图版本升序；历史图只读。 */
    List<CargoPlanRow> findPlansByFlight(long flightId);

    Optional<CargoPlanRow> findPlanById(long planId);

    List<CargoItemRow> findPlanItems(long planId);

    /** 给定器号集合中仍被占用（未关闭航班）的实物占用行。 */
    List<CargoOccupancyRow> findActiveOccupanciesByUldNos(java.util.Collection<String> uldNos);

    long insertPlan(CargoPlanRow row);

    void insertPlanItems(long planId, List<CargoItemRow> items);

    long insertOccupancy(CargoOccupancyRow row);

    /** 释放该航班全部未关闭占用（改图迁移或关闭放行原子调用）。 */
    int releaseOccupanciesByFlight(long flightId, java.time.LocalDateTime releasedAt);

    /** 装配提交作废旧双岗复核，使复核必须对应最新舱单/图。 */
    int deleteReviewsByManifest(long manifestId);
}
