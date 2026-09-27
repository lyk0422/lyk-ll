package com.example.starter.service;

import com.example.starter.domain.CustomsDeclaration;
import com.example.starter.domain.CustomsReservation;
import com.example.starter.domain.CustomsRiskEvent;
import com.example.starter.domain.ReservationStatus;
import com.example.starter.domain.Review;
import com.example.starter.domain.ReviewEvent;
import com.example.starter.domain.ReviewStatus;
import com.example.starter.dto.CustomsAmendRequest;
import com.example.starter.dto.CustomsBindRequest;
import com.example.starter.dto.CustomsDeclarationView;
import com.example.starter.dto.CustomsRegisterRequest;
import com.example.starter.dto.CustomsReservationView;
import com.example.starter.dto.CustomsRevokeRequest;
import com.example.starter.dto.CustomsWithdrawRequest;
import com.example.starter.dto.ErrorResponse;
import com.example.starter.dto.ServiceResult;
import com.example.starter.idempotency.Fingerprints;
import com.example.starter.idempotency.StrictIdempotencyExecutor;
import com.example.starter.json.JsonSupport;
import com.example.starter.repo.CustomsRepository;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.time.BusinessClock;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 进口包装子批通关单核销服务：登记、绑定预占、签发前撤回、撤销、修订与余量台账。
 *
 * <p>锁顺序统一为 通关单 → 审查流程 → 预占绑定，与签发热点一致，避免死锁。
 * 全部写操作经 {@link StrictIdempotencyExecutor}：同键同参重放、异参 409、失败不占键；
 * 业务、版本与幂等结果同事务提交。
 */
@Service
public class CustomsService {

    private final CustomsRepository customsRepository;
    private final ReviewRepository reviewRepository;
    private final StrictIdempotencyExecutor idempotency;
    private final JsonSupport jsonSupport;
    private final BusinessClock clock;

    public CustomsService(CustomsRepository customsRepository,
                          ReviewRepository reviewRepository,
                          StrictIdempotencyExecutor idempotency,
                          JsonSupport jsonSupport,
                          BusinessClock clock) {
        this.customsRepository = customsRepository;
        this.reviewRepository = reviewRepository;
        this.idempotency = idempotency;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
    }

    // ============================ 通关登记 ============================

    /** 登记合成通关单（requestId 幂等）。单号唯一，版本自 1 起。 */
    public ServiceResult register(CustomsRegisterRequest request) {
        String fingerprint = Fingerprints.customsRegister(request);
        return idempotency.execute(request.requestId(), Fingerprints.CUSTOMS_REGISTER, fingerprint,
                () -> doRegister(request));
    }

    private ServiceResult doRegister(CustomsRegisterRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = new CustomsDeclaration();
        declaration.setDeclarationNo(request.declarationNo().trim());
        declaration.setEnterpriseName(request.enterpriseName().trim());
        declaration.setProductName(request.productName().trim());
        declaration.setValidUntil(request.validUntil());
        declaration.setTotalQuantity(request.totalQuantity());
        declaration.setVersion(1);
        declaration.setRevoked(false);
        declaration.setCreatedAt(now);
        declaration.setUpdatedAt(now);

        Long id = customsRepository.insertDeclaration(declaration);
        if (id == null) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_DECLARATION_EXISTS",
                    "通关单号已登记: " + request.declarationNo()));
        }
        declaration.setId(id);
        customsRepository.insertRiskEvent(new CustomsRiskEvent(id, null, "CUSTOMS_REGISTERED",
                jsonSupport.write(Map.of(
                        "declarationNo", declaration.getDeclarationNo(),
                        "totalQuantity", declaration.getTotalQuantity(),
                        "validUntil", declaration.getValidUntil().toString())), now));
        return ServiceResult.ok(toView(declaration));
    }

    // ============================ 绑定预占 ============================

    /**
     * 包装子批绑定通关单（requestId 幂等）。绑定即预占申报支数；
     * 企业品种必须一致，累计预占不超总数（恰占满合法），任一资料不符整笔拒绝。
     */
    public ServiceResult bind(CustomsBindRequest request) {
        String fingerprint = Fingerprints.customsBind(request);
        return idempotency.execute(request.requestId(), Fingerprints.CUSTOMS_BIND, fingerprint,
                () -> doBind(request));
    }

    private ServiceResult doBind(CustomsBindRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = customsRepository.lockDeclaration(request.declarationNo().trim());
        if (declaration == null) {
            return ServiceResult.status(404, new ErrorResponse("CUSTOMS_NOT_FOUND",
                    "通关单不存在: " + request.declarationNo()));
        }
        ServiceResult versionCheck = checkVersion(declaration.getVersion(), request.expectedVersion());
        if (versionCheck != null) {
            return versionCheck;
        }
        if (declaration.isRevoked()) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_REVOKED",
                    "通关单已撤销，不能绑定子批"));
        }
        LocalDate todayUtc = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!todayUtc.isBefore(declaration.getValidUntil())) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_EXPIRED",
                    "通关单已于 " + declaration.getValidUntil() + "（UTC）截止，不能绑定子批"));
        }

        Review review = reviewRepository.lockByBatchNo(request.batchNo().trim());
        if (review == null) {
            return ServiceResult.status(404, new ErrorResponse("REVIEW_NOT_FOUND",
                    "批号不存在: " + request.batchNo()));
        }
        if (review.getStatus() == ReviewStatus.ISSUED
                || review.getStatus() == ReviewStatus.REJECTED
                || review.getStatus() == ReviewStatus.WITHDRAWN) {
            return ServiceResult.status(422, new ErrorResponse("REVIEW_FINAL_STATE",
                    "子批已处于终态 " + review.getStatus() + "，不能绑定通关单"));
        }
        // 任一资料不符整笔拒绝：企业、品种必须与通关单登记一致。
        if (!declaration.getEnterpriseName().equals(review.getEnterpriseName())
                || !declaration.getProductName().equals(review.getProductName())) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_MATERIAL_MISMATCH",
                    "子批企业/品种与通关单登记不一致，整笔拒绝"));
        }
        if (customsRepository.findReservationByReview(review.getId()) != null) {
            return ServiceResult.status(422, new ErrorResponse("BATCH_ALREADY_BOUND",
                    "该子批已绑定通关单，每个子批只能绑定一张单"));
        }

        int reserved = customsRepository.sumReservedQuantity(declaration.getId());
        if (reserved + request.declaredQuantity() > declaration.getTotalQuantity()) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_QUOTA_EXCEEDED",
                    "累计预占 " + (reserved + request.declaredQuantity())
                            + " 支将超过通关单可核销总数 " + declaration.getTotalQuantity() + " 支"));
        }

        CustomsReservation reservation = new CustomsReservation();
        reservation.setCustomsId(declaration.getId());
        reservation.setReviewId(review.getId());
        reservation.setDeclaredQuantity(request.declaredQuantity());
        reservation.setStatus(ReservationStatus.RESERVED);
        reservation.setVersion(1);
        reservation.setReservedAt(now);
        reservation.setCreatedAt(now);
        reservation.setUpdatedAt(now);
        Long reservationId = customsRepository.insertReservation(reservation);
        if (reservationId == null) {
            // 子批唯一约束落败：并发绑定同一子批仅唯一受理。
            return ServiceResult.status(422, new ErrorResponse("BATCH_ALREADY_BOUND",
                    "该子批已绑定通关单，每个子批只能绑定一张单"));
        }
        reservation.setId(reservationId);

        // 成功变更：通关单版本 +1（绑定改变了余量台账）。
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclarationVersioned(declaration, request.expectedVersion());

        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "CUSTOMS_BOUND",
                jsonSupport.write(Map.of(
                        "declarationNo", declaration.getDeclarationNo(),
                        "declaredQuantity", reservation.getDeclaredQuantity())), now));
        return ServiceResult.ok(toView(declaration, review, reservation));
    }

    // ============================ 签发前撤回 ============================

    /**
     * 签发前撤回子批（requestId 幂等）：释放本子批预占且仅释放一次，子批流程转撤回终态。
     */
    public ServiceResult withdraw(CustomsWithdrawRequest request) {
        String fingerprint = Fingerprints.customsWithdraw(request);
        return idempotency.execute(request.requestId(), Fingerprints.CUSTOMS_WITHDRAW, fingerprint,
                () -> doWithdraw(request));
    }

    private ServiceResult doWithdraw(CustomsWithdrawRequest request) {
        Instant now = clock.now();
        Review probe = reviewRepository.findByBatchNo(request.batchNo().trim());
        if (probe == null) {
            return ServiceResult.status(404, new ErrorResponse("REVIEW_NOT_FOUND",
                    "批号不存在: " + request.batchNo()));
        }
        CustomsReservation reservationProbe = customsRepository.findReservationByReview(probe.getId());
        if (reservationProbe == null) {
            return ServiceResult.status(404, new ErrorResponse("RESERVATION_NOT_FOUND",
                    "该子批未绑定通关单: " + request.batchNo()));
        }
        // 锁顺序：通关单 → 审查流程 → 预占绑定。
        customsRepository.lockDeclarationById(reservationProbe.getCustomsId());
        Review review = reviewRepository.lockByBatchNo(request.batchNo().trim());
        CustomsReservation reservation = customsRepository.lockReservationByReview(review.getId());

        ServiceResult versionCheck = checkVersion(reservation.getVersion(), request.expectedVersion());
        if (versionCheck != null) {
            return versionCheck;
        }
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return ServiceResult.status(422, new ErrorResponse("RESERVATION_ALREADY_RELEASED",
                    "预占已释放，释放只发生一次"));
        }
        if (reservation.getStatus() == ReservationStatus.WRITTEN_OFF) {
            return ServiceResult.status(422, new ErrorResponse("RESERVATION_ALREADY_WRITTEN_OFF",
                    "子批已签发核销，不能撤回"));
        }
        if (review.getStatus() == ReviewStatus.ISSUED) {
            return ServiceResult.status(422, new ErrorResponse("REVIEW_ALREADY_ISSUED",
                    "子批已签发归档，不能撤回"));
        }

        reservation.setStatus(ReservationStatus.RELEASED);
        reservation.setReleasedAt(now);
        reservation.setVersion(reservation.getVersion() + 1);
        reservation.setUpdatedAt(now);
        customsRepository.updateReservationVersioned(reservation, request.expectedVersion());

        review.setStatus(ReviewStatus.WITHDRAWN);
        review.setUpdatedAt(now);
        reviewRepository.update(review);

        reviewRepository.insertEvent(new ReviewEvent(review.getId(), "WITHDRAWN",
                jsonSupport.write(Map.of("reason", "CUSTOMS_WITHDRAWN_BEFORE_ISSUE",
                        "declarationNo", declarationNoOf(reservation))), now));
        customsRepository.insertRiskEvent(new CustomsRiskEvent(reservation.getCustomsId(), review.getId(),
                "RESERVATION_RELEASED",
                jsonSupport.write(Map.of("reason", "WITHDRAWN_BEFORE_ISSUE",
                        "declaredQuantity", reservation.getDeclaredQuantity())), now));
        return ServiceResult.ok(toView(null, review, reservation));
    }

    // ============================ 通关单撤销 ============================

    /**
     * 撤销通关单（requestId 幂等）：全部未签发子批预占释放并阻断签发；
     * 已签发证据保持不变，逐笔记风险事件。
     */
    public ServiceResult revoke(CustomsRevokeRequest request) {
        String fingerprint = Fingerprints.customsRevoke(request);
        return idempotency.execute(request.requestId(), Fingerprints.CUSTOMS_REVOKE, fingerprint,
                () -> doRevoke(request));
    }

    private ServiceResult doRevoke(CustomsRevokeRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = customsRepository.lockDeclaration(request.declarationNo().trim());
        if (declaration == null) {
            return ServiceResult.status(404, new ErrorResponse("CUSTOMS_NOT_FOUND",
                    "通关单不存在: " + request.declarationNo()));
        }
        ServiceResult versionCheck = checkVersion(declaration.getVersion(), request.expectedVersion());
        if (versionCheck != null) {
            return versionCheck;
        }
        if (declaration.isRevoked()) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_ALREADY_REVOKED",
                    "通关单已撤销，不能重复撤销"));
        }

        declaration.setRevoked(true);
        declaration.setRevokedAt(now);
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclarationVersioned(declaration, request.expectedVersion());

        List<CustomsReservation> reservations = customsRepository.lockReservations(declaration.getId());
        int released = 0;
        int issuedKept = 0;
        for (CustomsReservation reservation : reservations) {
            if (reservation.getStatus() == ReservationStatus.RESERVED) {
                // 未签发子批：预占释放（仅一次），签发随之被阻断。
                reservation.setStatus(ReservationStatus.RELEASED);
                reservation.setReleasedAt(now);
                reservation.setVersion(reservation.getVersion() + 1);
                reservation.setUpdatedAt(now);
                customsRepository.updateReservationVersioned(reservation, reservation.getVersion() - 1);
                customsRepository.insertRiskEvent(new CustomsRiskEvent(declaration.getId(),
                        reservation.getReviewId(), "RESERVATION_RELEASED",
                        jsonSupport.write(Map.of("reason", "CUSTOMS_REVOKED",
                                "declaredQuantity", reservation.getDeclaredQuantity())), now));
                released++;
            } else if (reservation.getStatus() == ReservationStatus.WRITTEN_OFF) {
                // 已签发子批：证据保持不变，记风险事件。
                customsRepository.insertRiskEvent(new CustomsRiskEvent(declaration.getId(),
                        reservation.getReviewId(), "ISSUED_EVIDENCE_RETAINED_AFTER_REVOKE",
                        jsonSupport.write(Map.of("declaredQuantity", reservation.getDeclaredQuantity(),
                                "writtenOffAt", reservation.getWrittenOffAt().toString())), now));
                issuedKept++;
            }
        }
        customsRepository.insertRiskEvent(new CustomsRiskEvent(declaration.getId(), null, "CUSTOMS_REVOKED",
                jsonSupport.write(Map.of("releasedReservations", released,
                        "issuedEvidenceKept", issuedKept)), now));
        return ServiceResult.ok(toView(declaration));
    }

    // ============================ 通关单修订 ============================

    /**
     * 修订通关单（requestId 幂等）：仅允许增加未占余量（提高可核销总数），
     * 不修改历史核销、企业与品种。
     */
    public ServiceResult amend(CustomsAmendRequest request) {
        String fingerprint = Fingerprints.customsAmend(request);
        return idempotency.execute(request.requestId(), Fingerprints.CUSTOMS_AMEND, fingerprint,
                () -> doAmend(request));
    }

    private ServiceResult doAmend(CustomsAmendRequest request) {
        Instant now = clock.now();
        CustomsDeclaration declaration = customsRepository.lockDeclaration(request.declarationNo().trim());
        if (declaration == null) {
            return ServiceResult.status(404, new ErrorResponse("CUSTOMS_NOT_FOUND",
                    "通关单不存在: " + request.declarationNo()));
        }
        ServiceResult versionCheck = checkVersion(declaration.getVersion(), request.expectedVersion());
        if (versionCheck != null) {
            return versionCheck;
        }
        if (declaration.isRevoked()) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_REVOKED",
                    "通关单已撤销，不能修订"));
        }
        if (request.newTotalQuantity() <= declaration.getTotalQuantity()) {
            return ServiceResult.status(422, new ErrorResponse("CUSTOMS_AMEND_NOT_INCREASE",
                    "通关修订仅能增加未占余量：新总数 " + request.newTotalQuantity()
                            + " 必须大于当前总数 " + declaration.getTotalQuantity()));
        }

        // 仅提高总数；企业、品种与历史核销保持不变。
        declaration.setTotalQuantity(request.newTotalQuantity());
        declaration.setVersion(declaration.getVersion() + 1);
        declaration.setUpdatedAt(now);
        customsRepository.updateDeclarationVersioned(declaration, request.expectedVersion());

        customsRepository.insertRiskEvent(new CustomsRiskEvent(declaration.getId(), null, "CUSTOMS_AMENDED",
                jsonSupport.write(Map.of("newTotalQuantity", request.newTotalQuantity())), now));
        return ServiceResult.ok(toView(declaration));
    }

    // ============================ 台账查询 ============================

    /** 通关单余量台账：总数、预占、已核销、剩余可预占。 */
    public CustomsDeclarationView getDeclaration(String declarationNo) {
        CustomsDeclaration declaration = customsRepository.findDeclaration(declarationNo.trim());
        if (declaration == null) {
            throw new BusinessException(404, "CUSTOMS_NOT_FOUND", "通关单不存在: " + declarationNo);
        }
        return toView(declaration);
    }

    /** 通关单下全部子批预占绑定台账。 */
    public List<CustomsReservationView> listReservations(String declarationNo) {
        CustomsDeclaration declaration = customsRepository.findDeclaration(declarationNo.trim());
        if (declaration == null) {
            throw new BusinessException(404, "CUSTOMS_NOT_FOUND", "通关单不存在: " + declarationNo);
        }
        return customsRepository.listReservations(declaration.getId()).stream()
                .map(reservation -> {
                    Review review = reviewRepository.findById(reservation.getReviewId());
                    return toView(declaration, review, reservation);
                })
                .toList();
    }

    /** 通关单风险事件流水（撤销、释放、阻断等证据）。 */
    public List<Map<String, Object>> listRiskEvents(String declarationNo) {
        CustomsDeclaration declaration = customsRepository.findDeclaration(declarationNo.trim());
        if (declaration == null) {
            throw new BusinessException(404, "CUSTOMS_NOT_FOUND", "通关单不存在: " + declarationNo);
        }
        return customsRepository.listRiskEvents(declaration.getId()).stream()
                .map(event -> {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("seq", event.getId());
                    row.put("eventType", event.getEventType());
                    row.put("reviewId", event.getReviewId() == null ? "" : event.getReviewId().toString());
                    row.put("payload", event.getPayload() == null ? "" : event.getPayload());
                    row.put("eventAt", event.getEventAt());
                    return row;
                })
                .toList();
    }

    // ============================ 内部辅助 ============================

    /** 版本核对：不符整笔拒绝（422）。 */
    private ServiceResult checkVersion(int currentVersion, int expectedVersion) {
        if (currentVersion != expectedVersion) {
            return ServiceResult.status(422, new ErrorResponse("VERSION_MISMATCH",
                    "期望版本 " + expectedVersion + " 与当前版本 " + currentVersion + " 不符，整笔拒绝"));
        }
        return null;
    }

    private String declarationNoOf(CustomsReservation reservation) {
        CustomsDeclaration declaration = customsRepository.findDeclarationById(reservation.getCustomsId());
        return declaration == null ? "" : declaration.getDeclarationNo();
    }

    private CustomsDeclarationView toView(CustomsDeclaration declaration) {
        int reserved = customsRepository.sumReservedQuantity(declaration.getId());
        int writtenOff = customsRepository.sumWrittenOffQuantity(declaration.getId());
        return new CustomsDeclarationView(
                declaration.getDeclarationNo(),
                declaration.getEnterpriseName(),
                declaration.getProductName(),
                declaration.getValidUntil(),
                declaration.getTotalQuantity(),
                reserved,
                writtenOff,
                declaration.getTotalQuantity() - reserved - writtenOff,
                declaration.getVersion(),
                declaration.isRevoked(),
                declaration.getRevokedAt());
    }

    private CustomsReservationView toView(CustomsDeclaration declaration, Review review,
                                          CustomsReservation reservation) {
        String declarationNo = declaration != null ? declaration.getDeclarationNo() : declarationNoOf(reservation);
        return new CustomsReservationView(
                declarationNo,
                review.getBatchNo(),
                reservation.getDeclaredQuantity(),
                reservation.getStatus().name(),
                reservation.getVersion(),
                reservation.getReservedAt(),
                reservation.getReleasedAt(),
                reservation.getWrittenOffAt());
    }
}
