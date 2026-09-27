package com.example.starter.ride;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.ride.Idempotency.ApiResult;
import com.example.starter.ride.dto.RideDtos.CheckItem;
import com.example.starter.ride.dto.RideDtos.IssueCertificateRequest;
import com.example.starter.ride.dto.RideDtos.OverhaulRequest;
import com.example.starter.ride.dto.RideDtos.RevokeCertificateRequest;
import com.example.starter.ride.dto.RideDtos.SupervisionInspectionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 年度检验与大修后监督检验门禁服务。
 * 所有写操作携带 requestId（幂等）与 expectedVersion（乐观锁，版本从1开始、成功变更加1），
 * 业务、版本与幂等结果在同一事务内提交；失败不占键。
 */
@Service
public class InspectionService {

    private final RideRepository rides;
    private final RideRecordRepository records;
    private final CertificateRepository certificates;
    private final SupervisionRepository supervisions;
    private final InspectionGate gate;
    private final Idempotency.Executor idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InspectionService(RideRepository rides, RideRecordRepository records,
            CertificateRepository certificates, SupervisionRepository supervisions,
            InspectionGate gate, Idempotency.Executor idempotency,
            ObjectMapper objectMapper, Clock clock) {
        this.rides = rides;
        this.records = records;
        this.certificates = certificates;
        this.supervisions = supervisions;
        this.gate = gate;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 签发/换证：证书固定设备编号、检验合格时刻及有效截止（默认合格时刻+365×24小时）；
     * 换证原子切换唯一当前指针，历史证书不可覆盖。
     */
    public ApiResult issueCertificate(long rideId, IssueCertificateRequest request) {
        return idempotency.execute(request.requestId(), "CERT_ISSUE", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireNotArchived(ride, "签发年度检验证书");
            requireVersion(ride, request.expectedVersion());
            Instant passedAt = request.passedAt() == null ? Instant.now(clock) : request.passedAt();
            Instant validUntil = request.validUntil() == null
                    ? passedAt.plus(AnnualCertificate.DEFAULT_VALIDITY) : request.validUntil();
            if (!validUntil.isAfter(passedAt)) {
                throw ApiException.badRequest("有效截止必须晚于检验合格时刻");
            }
            AnnualCertificate cert = certificates.insert(rideId, request.requestId(),
                    passedAt, validUntil);
            if (!rides.updateCurrentCertificate(ride, cert.id())) {
                throw ApiException.conflict("设备状态并发变更，请重试");
            }
            records.insertEvent(rideId, "CERT_ISSUE",
                    "签发年度检验证书#" + cert.id() + "，合格时刻=" + passedAt
                            + "，有效截止=" + validUntil + "，原子切换当前指针");
            Ride fresh = rides.findById(rideId).orElseThrow();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("certificateId", cert.id());
            body.put("rideId", rideId);
            body.put("passedAt", passedAt.toString());
            body.put("validUntil", validUntil.toString());
            body.put("current", true);
            body.put("rideVersion", fresh.version());
            return ok(body);
        });
    }

    /** 撤销证书：撤销后当前证书立即失效，与开放竞态不得出现失效资格仍新开放。 */
    public ApiResult revokeCertificate(long rideId, long certificateId,
            RevokeCertificateRequest request) {
        return idempotency.execute(request.requestId(), "CERT_REVOKE", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireVersion(ride, request.expectedVersion());
            AnnualCertificate cert = certificates.findById(certificateId)
                    .filter(c -> c.rideId() == rideId)
                    .orElseThrow(() -> ApiException.notFound(
                            "证书不存在：" + certificateId));
            if (cert.revoked()) {
                throw ApiException.unprocessable("证书已撤销，拒绝重复撤销：" + certificateId);
            }
            Instant now = Instant.now(clock);
            if (!certificates.revoke(certificateId, now)) {
                throw ApiException.conflict("证书状态并发变更，请重试");
            }
            if (!rides.touchVersion(ride)) {
                throw ApiException.conflict("设备状态并发变更，请重试");
            }
            records.insertEvent(rideId, "CERT_REVOKE", "撤销年度检验证书#" + certificateId);
            Ride fresh = rides.findById(rideId).orElseThrow();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("certificateId", certificateId);
            body.put("rideId", rideId);
            body.put("revoked", true);
            body.put("revokedAt", now.toString());
            body.put("rideVersion", fresh.version());
            return ok(body);
        });
    }

    /**
     * 登记大修：原子递增大修版本、暂停开放（开放中设备转为故障停运待复检）
     * 并使旧监督检验失效；旧年度证书可查但不足以复开。
     */
    public ApiResult registerOverhaul(long rideId, OverhaulRequest request) {
        return idempotency.execute(request.requestId(), "OVERHAUL", rideId, request, () -> {
            Ride ride = lock(rideId);
            requireNotArchived(ride, "登记大修");
            requireVersion(ride, request.expectedVersion());
            Instant registeredAt = request.registeredAt() == null
                    ? Instant.now(clock) : request.registeredAt();
            long newOverhaulVersion = ride.overhaulVersion() + 1;
            boolean wasOpen = ride.status() == RideStatus.OPEN;
            RideStatus newStatus = wasOpen ? RideStatus.STOPPED : ride.status();
            long overhaulId = supervisions.insertOverhaul(rideId, request.requestId(),
                    newOverhaulVersion, registeredAt, request.note());
            if (!rides.updateOverhaul(ride, newOverhaulVersion, newStatus)) {
                throw ApiException.conflict("设备状态并发变更，请重试");
            }
            records.insertEvent(rideId, "OVERHAUL",
                    "登记大修#" + overhaulId + "，大修版本升至v" + newOverhaulVersion
                            + "，旧监督检验失效" + (wasOpen ? "，暂停开放" : ""));
            Ride fresh = rides.findById(rideId).orElseThrow();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("overhaulId", overhaulId);
            body.put("rideId", rideId);
            body.put("overhaulVersion", newOverhaulVersion);
            body.put("status", fresh.status().name());
            body.put("openingSuspended", wasOpen);
            body.put("rideVersion", fresh.version());
            return ok(body);
        });
    }

    /**
     * 大修后监督检验确认：须绑定当前大修版本，两名不同检验人共同确认全清单合格；
     * 确认后只恢复开放资格，不自动开放，也不重置原当日复开次数。
     */
    public ApiResult confirmSupervision(long rideId, SupervisionInspectionRequest request) {
        return idempotency.execute(request.requestId(), "SUPERVISION_INSPECTION", rideId,
                request, () -> {
                    Ride ride = lock(rideId);
                    requireNotArchived(ride, "监督检验确认");
                    requireVersion(ride, request.expectedVersion());
                    if (request.overhaulVersion() != ride.overhaulVersion()) {
                        throw ApiException.unprocessable(
                                "监督检验须绑定当前大修版本：请求v" + request.overhaulVersion()
                                        + "，当前v" + ride.overhaulVersion());
                    }
                    if (request.inspectorOne().equals(request.inspectorTwo())) {
                        throw ApiException.unprocessable(
                                "两名检验人须为不同人员：" + request.inspectorOne());
                    }
                    boolean allPassed = request.items().stream().allMatch(CheckItem::passed);
                    Instant confirmedAt = Instant.now(clock);
                    long inspectionId = supervisions.insertInspection(rideId, request.requestId(),
                            request.overhaulVersion(), request.inspectorOne(),
                            request.inspectorTwo(), allPassed, confirmedAt, request.items());
                    if (!rides.touchVersion(ride)) {
                        throw ApiException.conflict("设备状态并发变更，请重试");
                    }
                    records.insertEvent(rideId, "SUPERVISION_INSPECTION",
                            "监督检验#" + inspectionId + "绑定大修版本v" + request.overhaulVersion()
                                    + "，检验人=" + request.inspectorOne() + "、"
                                    + request.inspectorTwo()
                                    + (allPassed ? "，全清单合格，恢复开放资格（不自动开放）" : "，存在不合格项"));
                    Ride fresh = rides.findById(rideId).orElseThrow();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("inspectionId", inspectionId);
                    body.put("rideId", rideId);
                    body.put("overhaulVersion", request.overhaulVersion());
                    body.put("allPassed", allPassed);
                    body.put("eligibilityRestored", allPassed);
                    body.put("status", fresh.status().name());
                    body.put("reopenCount", fresh.reopenCount());
                    body.put("rideVersion", fresh.version());
                    return ok(body);
                });
    }

    /** 设备全部年度证书（含历史与已撤销），附当前指针与有效性标记。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCertificates(long rideId) {
        Ride ride = ensureExists(rideId);
        Instant now = Instant.now(clock);
        return certificates.listByRide(rideId).stream()
                .map(cert -> gate.certificateView(cert, ride, now))
                .toList();
    }

    /** 大修登记记录。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOverhauls(long rideId) {
        ensureExists(rideId);
        return supervisions.listOverhauls(rideId);
    }

    /** 监督检验记录（含清单明细），可复算。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listInspections(long rideId) {
        ensureExists(rideId);
        List<Map<String, Object>> inspections = supervisions.listInspections(rideId);
        for (Map<String, Object> inspection : inspections) {
            long inspectionId = ((Number) inspection.get("id")).longValue();
            inspection.put("items", supervisions.listInspectionItems(inspectionId));
        }
        return inspections;
    }

    /** 开放资格证据：证书、撤销、监督检验与门禁结论。 */
    @Transactional(readOnly = true)
    public Map<String, Object> eligibility(long rideId) {
        return gate.evidence(ensureExists(rideId));
    }

    private Ride ensureExists(long rideId) {
        return rides.findById(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId));
    }

    private Ride lock(long rideId) {
        return rides.findByIdForUpdate(rideId)
                .orElseThrow(() -> ApiException.notFound("设备不存在：" + rideId));
    }

    private void requireNotArchived(Ride ride, String operation) {
        if (ride.status() == RideStatus.ARCHIVED) {
            throw ApiException.unprocessable("设备已归档，拒绝" + operation);
        }
    }

    private void requireVersion(Ride ride, long expectedVersion) {
        if (ride.version() != expectedVersion) {
            throw ApiException.conflict("expectedVersion与设备当前版本不一致：期望"
                    + expectedVersion + "，当前" + ride.version());
        }
    }

    private ApiResult ok(Map<String, Object> body) {
        try {
            return new ApiResult(200, objectMapper.writeValueAsString(body));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
