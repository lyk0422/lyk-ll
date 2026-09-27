package com.example.starter.ride;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 年度检验与大修后开放资格门禁：受检设备开放或复开须同时满足
 * 原日检、空载试运行规则与当前有效年度证书；登记大修后还须
 * 绑定当前大修版本的监督检验确认。未启用受检标志的旧设备直接放行。
 */
@Component
public class InspectionGate {

    private final CertificateRepository certificates;
    private final SupervisionRepository supervisions;
    private final Clock clock;

    public InspectionGate(CertificateRepository certificates,
            SupervisionRepository supervisions, Clock clock) {
        this.certificates = certificates;
        this.supervisions = supervisions;
        this.clock = clock;
    }

    /** 当前证书（唯一当前指针指向的证书），无指针或证书缺失时为 null。 */
    public AnnualCertificate currentCertificate(Ride ride) {
        if (ride.currentCertificateId() == null) {
            return null;
        }
        return certificates.findById(ride.currentCertificateId()).orElse(null);
    }

    /** 当前证书是否有效：存在、未撤销且 UTC 下 now < 有效截止（恰截止失效）。 */
    public boolean certificateValid(Ride ride) {
        AnnualCertificate cert = currentCertificate(ride);
        return cert != null && cert.validAt(Instant.now(clock));
    }

    /** 是否需要大修后监督检验：受检设备且已登记过大修。 */
    public boolean supervisionRequired(Ride ride) {
        return ride.inspectionRequired() && ride.overhaulVersion() > 0;
    }

    /** 监督检验是否满足：无需监督检验，或存在绑定当前大修版本且全清单合格的确认。 */
    public boolean supervisionSatisfied(Ride ride) {
        return !supervisionRequired(ride)
                || supervisions.existsConfirmedInspection(ride.id(), ride.overhaulVersion());
    }

    /** 门禁是否通过：未启用受检标志的旧设备直接通过。 */
    public boolean gatePassed(Ride ride) {
        return !ride.inspectionRequired()
                || (certificateValid(ride) && supervisionSatisfied(ride));
    }

    /** 开放/复开资格校验：受检设备须当前有效年度证书且（大修后）监督检验已确认。 */
    public void requireOpenEligibility(Ride ride) {
        if (!ride.inspectionRequired()) {
            return;
        }
        if (!certificateValid(ride)) {
            throw ApiException.unprocessable(
                    "无当前有效年度检验证书，受检设备禁止开放或复开");
        }
        if (!supervisionSatisfied(ride)) {
            throw ApiException.unprocessable(
                    "大修后未完成绑定当前大修版本(v" + ride.overhaulVersion()
                            + ")的监督检验，禁止开放或复开");
        }
    }

    /** 资格证据：证书、撤销、监督检验与门禁结论，可复算。 */
    public Map<String, Object> evidence(Ride ride) {
        Instant now = Instant.now(clock);
        AnnualCertificate cert = currentCertificate(ride);
        boolean certValid = cert != null && cert.validAt(now);
        boolean supervisionOk = supervisionSatisfied(ride);
        List<String> reasons = new ArrayList<>();
        if (ride.inspectionRequired()) {
            if (cert == null) {
                reasons.add("无当前年度检验证书");
            } else if (cert.revoked()) {
                reasons.add("当前年度检验证书已撤销");
            } else if (!certValid) {
                reasons.add("当前年度检验证书已过期（恰截止即失效）");
            }
            if (!supervisionOk) {
                reasons.add("缺少绑定当前大修版本(v" + ride.overhaulVersion() + ")的监督检验确认");
            }
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rideId", ride.id());
        view.put("status", ride.status().name());
        view.put("inspectionRequired", ride.inspectionRequired());
        view.put("overhaulVersion", ride.overhaulVersion());
        view.put("currentCertificate", cert == null ? null : certificateView(cert, ride, now));
        view.put("supervisionRequired", supervisionRequired(ride));
        view.put("supervisionSatisfied", supervisionOk);
        view.put("gatePassed", !ride.inspectionRequired() || (certValid && supervisionOk));
        view.put("reasons", reasons);
        view.put("evaluatedAt", now.toString());
        return view;
    }

    /** 证书视图：含撤销与当前指针标记，供证书/撤销证据查询。 */
    public Map<String, Object> certificateView(AnnualCertificate cert, Ride ride, Instant now) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("certificateId", cert.id());
        view.put("rideId", cert.rideId());
        view.put("passedAt", cert.passedAt().toString());
        view.put("validUntil", cert.validUntil().toString());
        view.put("revoked", cert.revoked());
        view.put("revokedAt", cert.revokedAt() == null ? null : cert.revokedAt().toString());
        view.put("current", ride.currentCertificateId() != null
                && ride.currentCertificateId() == cert.id());
        view.put("valid", cert.validAt(now));
        return view;
    }
}
