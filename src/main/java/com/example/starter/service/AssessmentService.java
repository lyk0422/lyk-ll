package com.example.starter.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.domain.RouteStatus;
import com.example.starter.error.BusinessException;
import com.example.starter.idempotency.IdempotencyService;
import com.example.starter.repo.AssessmentRepository;
import com.example.starter.repo.QualificationRepository;
import com.example.starter.repo.RouteRepository;
import com.example.starter.time.TimeProvider;
import com.example.starter.web.dto.RouteDtos.AssessmentResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 安全评估服务：站点间距、道路等级与候选车辆座位数逐项校验，
 * 任一项不满足拒绝，恰等限值通过；评估通过生成 1 年有效期运行资格。
 */
@Service
public class AssessmentService {

    /** 站点间距下限，单位米，恰等通过。 */
    public static final int MIN_SPACING_M = 500;
    /** 站点间距上限，单位米，恰等通过。 */
    public static final int MAX_SPACING_M = 3000;
    /** 道路等级限值（数值越小等级越高），恰等通过。 */
    public static final int MAX_ROAD_GRADE = 3;
    /** 资格有效期年数。 */
    public static final int VALIDITY_YEARS = 1;

    private final RouteRepository routeRepository;
    private final AssessmentRepository assessmentRepository;
    private final QualificationRepository qualificationRepository;
    private final IdempotencyService idempotencyService;
    private final TimeProvider timeProvider;

    public AssessmentService(RouteRepository routeRepository,
                             AssessmentRepository assessmentRepository,
                             QualificationRepository qualificationRepository,
                             IdempotencyService idempotencyService,
                             TimeProvider timeProvider) {
        this.routeRepository = routeRepository;
        this.assessmentRepository = assessmentRepository;
        this.qualificationRepository = qualificationRepository;
        this.idempotencyService = idempotencyService;
        this.timeProvider = timeProvider;
    }

    /**
     * 执行安全评估（幂等）；同线路同日期重复评估拒绝。
     */
    @Transactional
    public AssessmentResponse assess(long routeId, String requestId) {
        LocalDate today = timeProvider.today();
        String fingerprint = idempotencyService.fingerprint(requestId, routeId, today);
        return idempotencyService.execute("POST /api/routes/{id}/assessments", requestId,
                fingerprint, AssessmentResponse.class, () -> doAssess(routeId, requestId, today));
    }

    private AssessmentResponse doAssess(long routeId, String requestId, LocalDate today) {
        RouteRepository.RouteRow route = routeRepository.findById(routeId)
                .orElseThrow(() -> BusinessException.notFound("线路不存在: " + routeId));
        if (RouteStatus.SUSPENDED.name().equals(route.status())) {
            throw BusinessException.conflict("ROUTE_SUSPENDED", "停运线路不允许评估: " + routeId);
        }

        boolean spacingOk = route.stationSpacingM() >= MIN_SPACING_M
                && route.stationSpacingM() <= MAX_SPACING_M;
        boolean roadOk = route.roadGrade() <= MAX_ROAD_GRADE;
        int seatCapacity = routeRepository.vehicleSeatCapacity(route.vehicleId())
                .orElseThrow(() -> BusinessException.unprocessable("VEHICLE_NOT_FOUND",
                        "候选车辆不存在: " + route.vehicleId()));
        boolean seatsOk = seatCapacity >= route.requiredSeats();

        boolean passed = spacingOk && roadOk && seatsOk;
        String result = passed ? "PASSED" : "REJECTED";
        String rejectReason = passed ? null
                : buildRejectReason(route, spacingOk, roadOk, seatsOk, seatCapacity);

        long assessmentId;
        try {
            assessmentId = assessmentRepository.insert(routeId, today, spacingOk, roadOk, seatsOk,
                    result, rejectReason, requestId);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("ASSESSMENT_DUPLICATE",
                    "同线路同日期重复评估: routeId=" + routeId + ", date=" + today);
        }

        Long qualificationId = null;
        LocalDate validFrom = null;
        LocalDate validTo = null;
        if (passed) {
            validFrom = today;
            validTo = today.plusYears(VALIDITY_YEARS).minusDays(1);
            qualificationId = qualificationRepository.insert(routeId, validFrom, validTo,
                    "INITIAL", requestId);
            routeRepository.updateStatus(routeId, RouteStatus.QUALIFIED);
        }
        return new AssessmentResponse(assessmentId, routeId, today, spacingOk, roadOk, seatsOk,
                result, rejectReason, qualificationId, validFrom, validTo);
    }

    private String buildRejectReason(RouteRepository.RouteRow route, boolean spacingOk,
                                     boolean roadOk, boolean seatsOk, int seatCapacity) {
        List<String> reasons = new ArrayList<>();
        if (!spacingOk) {
            reasons.add("站点间距" + route.stationSpacingM() + "米不在[" + MIN_SPACING_M + ", "
                    + MAX_SPACING_M + "]范围");
        }
        if (!roadOk) {
            reasons.add("道路等级" + route.roadGrade() + "高于限值" + MAX_ROAD_GRADE);
        }
        if (!seatsOk) {
            reasons.add("车辆座位数" + seatCapacity + "小于所需座位数" + route.requiredSeats());
        }
        return String.join("; ", reasons);
    }
}
