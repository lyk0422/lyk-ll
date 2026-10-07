package com.example.starter.ride;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.LockRemovalRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceCreateRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceFinishRequest;
import com.example.starter.ride.dto.RideDtos.MaintenanceStartRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 检修挂牌流程：建档(PREPARED)→启动(整机停运+每人唯一未撤锁+清单冻结)→
 * 双人签认逐人撤锁→全部撤除后结束(仅清除挂牌阻断)。
 * 不替代原年度证书、大修监督检验、日检与空载试运行条件；
 * 所有流转在事务内持设备行锁串行执行，停运、锁、版本与幂等记录同事务提交。
 */
@Service
public class MaintenanceService {

    private final RideRepository rides;
    private final MaintenanceRepository maintenance;
    private final RideRecordRepository records;
    private final Idempotency.Executor idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MaintenanceService(RideRepository rides, MaintenanceRepository maintenance,
            RideRecordRepository records, Idempotency.Executor idempotency,
            ObjectMapper objectMapper, Clock clock) {
        this.rides = rides;
        this.maintenance = maintenance;
        this.records = records;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 建档检修单：绑定设备与1至20名不同挂牌人员，初始PREPARED、版本从1开始。 */
    public ApiResult create(long rideId, MaintenanceCreateRequest request) {
        return idempotency.execute(request.requestId(), "MAINT_CREATE", rideId, request, () -> {
            Ride ride = lock(rideId);
            if (ride.status() == RideStatus.ARCHIVED) {
                throw ApiException.conflict("设备已归档，拒绝建档检修单");
            }
            List<String> personnel = request.personnel();
            validatePersonnel(personnel);
            long maintenanceId = maintenance.insertOrder(rideId, request.requestId(), personnel);
            records.insertEvent(rideId, "MAINT_CREATE",
                    "检修单#" + maintenanceId + "建档，挂牌人员" + personnel.size() + "名："
                            + String.join("、", personnel));
            return ok(orderView(rideId, maintenanceId));
        });
    }

    /**
     * 启动检修：一个事务内整机停运并为每人建立唯一未撤锁，清单自此冻结；
     * 已载客(OPEN)、已归档或存在其他进行中检修单时拒绝启动。
     */
    public ApiResult start(long rideId, long maintenanceId, MaintenanceStartRequest request) {
        return idempotency.execute(request.requestId(), "MAINT_START", rideId, request, () -> {
            Ride ride = lock(rideId);
            MaintenanceOrder order = requireOrder(rideId, maintenanceId);
            if (order.status() != MaintenanceStatus.PREPARED) {
                throw ApiException.conflict("仅PREPARED检修单可启动（当前状态："
                        + order.status() + "）");
            }
            requireVersion(order, request.expectedVersion());
            if (ride.status() == RideStatus.OPEN) {
                throw ApiException.conflict("设备已载客，拒绝启动检修");
            }
            if (ride.status() == RideStatus.ARCHIVED) {
                throw ApiException.conflict("设备已归档，拒绝启动检修");
            }
            if (maintenance.existsInProgress(rideId)) {
                throw ApiException.conflict("同设备只允许一张进行中检修单，存在其他检修占用");
            }
            // 整机停运：保留日检、试运行与复开计数等原状态链字段
            if (!rides.updateWithVersion(ride, RideStatus.STOPPED, ride.trialCount(),
                    ride.reopenCount(), ride.checkDate(), ride.reopenDate())) {
                throw ApiException.conflict("设备状态并发变更，请重试");
            }
            List<String> personnel = maintenance.listPersonnel(maintenanceId);
            maintenance.insertLocks(maintenanceId, personnel);
            Instant now = Instant.now(clock);
            if (!maintenance.transition(maintenanceId, request.expectedVersion(),
                    MaintenanceStatus.IN_PROGRESS, now)) {
                throw ApiException.conflict("检修单并发变更，请重试");
            }
            records.insertEvent(rideId, "MAINT_START",
                    "检修单#" + maintenanceId + "启动，整机停运，为" + personnel.size()
                            + "名人员建立唯一未撤锁，清单冻结");
            return ok(orderView(rideId, maintenanceId));
        });
    }

    /**
     * 撤锁：每人的锁只能由本人和一名不同监护员共同确认撤除；
     * 单人或重复身份不生效，不能替未归还人员代签；签认只追加不覆盖。
     */
    public ApiResult removeLock(long rideId, long maintenanceId, LockRemovalRequest request) {
        return idempotency.execute(request.requestId(), "MAINT_UNLOCK", rideId, request, () -> {
            lock(rideId);
            MaintenanceOrder order = requireOrder(rideId, maintenanceId);
            if (order.status() != MaintenanceStatus.IN_PROGRESS) {
                throw ApiException.conflict("仅进行中检修单可撤锁（当前状态："
                        + order.status() + "）");
            }
            requireVersion(order, request.expectedVersion());
            if (request.personName().equals(request.supervisorName())) {
                throw ApiException.unprocessable("挂牌本人与监护员必须不同，单人或重复身份不生效");
            }
            MaintenanceLock lock = maintenance.findLock(maintenanceId, request.personName())
                    .orElseThrow(() -> ApiException.unprocessable(
                            "人员不在冻结挂牌清单，不能替未归还人员代签：" + request.personName()));
            if (lock.removed()) {
                throw ApiException.conflict("该人员的锁已撤除，签认不可覆盖：" + request.personName());
            }
            Instant now = Instant.now(clock);
            if (!maintenance.markLockRemoved(lock.id(), now)) {
                throw ApiException.conflict("撤锁并发变更，请重试");
            }
            maintenance.insertRemoval(lock.id(), request.personName(),
                    request.supervisorName(), now);
            if (!maintenance.bumpVersion(maintenanceId, request.expectedVersion())) {
                throw ApiException.conflict("检修单并发变更，请重试");
            }
            records.insertEvent(rideId, "MAINT_UNLOCK",
                    "检修单#" + maintenanceId + "撤锁：本人=" + request.personName()
                            + "，监护员=" + request.supervisorName());
            return ok(orderView(rideId, maintenanceId));
        });
    }

    /**
     * 结束检修：全部锁撤除后方可结束；只清除挂牌阻断，不自动开放，
     * 不重置日检、证书或当日复开次数；原故障停运不解除，须继续原复检流程。
     */
    public ApiResult finish(long rideId, long maintenanceId, MaintenanceFinishRequest request) {
        return idempotency.execute(request.requestId(), "MAINT_FINISH", rideId, request, () -> {
            lock(rideId);
            MaintenanceOrder order = requireOrder(rideId, maintenanceId);
            if (order.status() != MaintenanceStatus.IN_PROGRESS) {
                throw ApiException.conflict("仅进行中检修单可结束（当前状态："
                        + order.status() + "）");
            }
            requireVersion(order, request.expectedVersion());
            int unremoved = maintenance.countUnremovedLocks(maintenanceId);
            if (unremoved > 0) {
                throw ApiException.conflict("存在" + unremoved + "把未撤锁，清单不完整，拒绝结束检修");
            }
            Instant now = Instant.now(clock);
            if (!maintenance.transition(maintenanceId, request.expectedVersion(),
                    MaintenanceStatus.FINISHED, now)) {
                throw ApiException.conflict("检修单并发变更，请重试");
            }
            records.insertEvent(rideId, "MAINT_FINISH",
                    "检修单#" + maintenanceId + "结束，全部锁已撤除，仅清除挂牌阻断，不自动开放");
            return ok(orderView(rideId, maintenanceId));
        });
    }

    /** 设备全部检修单（含冻结清单、锁与双人签认），历史不可覆盖，可复算。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMaintenances(long rideId) {
        ensureExists(rideId);
        List<Map<String, Object>> views = new ArrayList<>();
        for (MaintenanceOrder order : maintenance.listOrders(rideId)) {
            views.add(orderView(rideId, order.id()));
        }
        return views;
    }

    /** 单张检修单详情：显示每个未撤锁人。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getMaintenance(long rideId, long maintenanceId) {
        ensureExists(rideId);
        requireOrder(rideId, maintenanceId);
        return orderView(rideId, maintenanceId);
    }

    private void validatePersonnel(List<String> personnel) {
        if (personnel == null || personnel.isEmpty()
                || personnel.size() > MaintenanceOrder.MAX_PERSONNEL) {
            throw ApiException.unprocessable("挂牌人员须为1至"
                    + MaintenanceOrder.MAX_PERSONNEL + "名");
        }
        if (new HashSet<>(personnel).size() != personnel.size()) {
            throw ApiException.unprocessable("挂牌人员必须互不相同，存在重复人员");
        }
    }

    private void requireVersion(MaintenanceOrder order, long expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("检修单版本冲突：expectedVersion=" + expectedVersion
                    + "，当前版本=" + order.version());
        }
    }

    private MaintenanceOrder requireOrder(long rideId, long maintenanceId) {
        return maintenance.findOrder(rideId, maintenanceId)
                .orElseThrow(() -> ApiException.notFound(
                        "检修单不存在：" + maintenanceId + "（设备：" + rideId + "）"));
    }

    private void ensureExists(long rideId) {
        if (rides.findById(rideId).isEmpty()) {
            throw ApiException.notFound("设备不存在：" + rideId);
        }
    }

    private Ride lock(long rideId) {
        return rides.findByIdForUpdate(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId));
    }

    private Map<String, Object> orderView(long rideId, long maintenanceId) {
        MaintenanceOrder order = requireOrder(rideId, maintenanceId);
        List<String> personnel = maintenance.listPersonnel(maintenanceId);
        List<MaintenanceLock> locks = maintenance.listLocks(maintenanceId);
        Map<Long, Map<String, Object>> removalsByLockId = new LinkedHashMap<>();
        for (Map<String, Object> removal : maintenance.listRemovals(maintenanceId)) {
            removalsByLockId.put(((Number) removal.get("lock_id")).longValue(), removal);
        }
        List<Map<String, Object>> lockViews = new ArrayList<>();
        List<String> unremovedPersonnel = new ArrayList<>();
        for (MaintenanceLock lock : locks) {
            Map<String, Object> lockView = new LinkedHashMap<>();
            lockView.put("lockId", lock.id());
            lockView.put("personName", lock.personName());
            lockView.put("removed", lock.removed());
            lockView.put("removedAt",
                    lock.removedAt() == null ? null : lock.removedAt().toString());
            Map<String, Object> removal = removalsByLockId.get(lock.id());
            if (removal != null) {
                lockView.put("confirmedBy", removal.get("person_name"));
                lockView.put("supervisedBy", removal.get("supervisor_name"));
            }
            lockViews.add(lockView);
            if (!lock.removed()) {
                unremovedPersonnel.add(lock.personName());
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("maintenanceId", order.id());
        view.put("rideId", order.rideId());
        view.put("status", order.status().name());
        view.put("version", order.version());
        view.put("startedAt", order.startedAt() == null ? null : order.startedAt().toString());
        view.put("finishedAt", order.finishedAt() == null ? null : order.finishedAt().toString());
        view.put("personnel", personnel);
        view.put("locks", lockViews);
        view.put("unremovedPersonnel", unremovedPersonnel);
        return view;
    }

    private ApiResult ok(Map<String, Object> body) {
        try {
            return new ApiResult(200, objectMapper.writeValueAsString(body));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
