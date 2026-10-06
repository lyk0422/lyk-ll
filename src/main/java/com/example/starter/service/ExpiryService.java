package com.example.starter.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.domain.QualificationStatus;
import com.example.starter.domain.RouteStatus;
import com.example.starter.idempotency.IdempotencyService;
import com.example.starter.repo.NotificationRepository;
import com.example.starter.repo.QualificationRepository;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.StudentRepository;
import com.example.starter.time.TimeProvider;
import com.example.starter.web.dto.QualificationDtos.ExpireResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资格到期处理服务：失效资格、停运线路并留痕，
 * 停运线路学生改派记录与通知在同一事务内生成，改派结果可按日结日期复算。
 */
@Service
public class ExpiryService {

    /** 停运/改派原因：资格到期。 */
    public static final String REASON_QUALIFICATION_EXPIRED = "QUALIFICATION_EXPIRED";

    private final QualificationRepository qualificationRepository;
    private final RouteRepository routeRepository;
    private final StudentRepository studentRepository;
    private final NotificationRepository notificationRepository;
    private final IdempotencyService idempotencyService;
    private final TimeProvider timeProvider;

    public ExpiryService(QualificationRepository qualificationRepository,
                         RouteRepository routeRepository,
                         StudentRepository studentRepository,
                         NotificationRepository notificationRepository,
                         IdempotencyService idempotencyService,
                         TimeProvider timeProvider) {
        this.qualificationRepository = qualificationRepository;
        this.routeRepository = routeRepository;
        this.studentRepository = studentRepository;
        this.notificationRepository = notificationRepository;
        this.idempotencyService = idempotencyService;
        this.timeProvider = timeProvider;
    }

    /**
     * 处理到期资格（幂等）：valid_to 当日 24:00（UTC 日界）失效，
     * 即 valid_to &lt; 今日的 ACTIVE 资格置为 EXPIRED，关联线路停运留痕，
     * 并为线路学生生成改派记录与合成通知（同一事务）。
     */
    @Transactional
    public ExpireResponse expireQualifications(String requestId) {
        LocalDate today = timeProvider.today();
        String fingerprint = idempotencyService.fingerprint(requestId, today);
        return idempotencyService.execute("POST /api/qualifications/expire", requestId,
                fingerprint, ExpireResponse.class, () -> doExpire(today));
    }

    private ExpireResponse doExpire(LocalDate today) {
        List<Long> expiredIds = new ArrayList<>();
        List<Long> suspendedRouteIds = new ArrayList<>();
        int reassignmentCount = 0;
        int notificationCount = 0;

        for (QualificationRepository.QualificationRow q
                : qualificationRepository.findExpiredActive(today)) {
            qualificationRepository.updateStatus(q.id(), QualificationStatus.EXPIRED);
            expiredIds.add(q.id());

            RouteRepository.RouteRow route = routeRepository.findById(q.routeId()).orElseThrow();
            if (!RouteStatus.SUSPENDED.name().equals(route.status())) {
                routeRepository.updateStatus(q.routeId(), RouteStatus.SUSPENDED);
                qualificationRepository.insertSuspensionTrace(q.routeId(), q.id(),
                        REASON_QUALIFICATION_EXPIRED);
                suspendedRouteIds.add(q.routeId());

                List<StudentRepository.StudentRow> students =
                        studentRepository.findStudentsByRoute(q.routeId());
                Long targetRouteId = routeRepository.findReassignmentTarget(q.routeId())
                        .orElse(null);
                for (StudentRepository.StudentRow student : students) {
                    notificationRepository.insertReassignment(student.id(), q.routeId(),
                            targetRouteId, REASON_QUALIFICATION_EXPIRED, today);
                    reassignmentCount++;
                    studentRepository.updateStudentRoute(student.id(), targetRouteId);
                    String content = targetRouteId == null
                            ? "线路" + q.routeId() + "资格到期停运，学生" + student.studentNo()
                            + "暂无可改派线路"
                            : "线路" + q.routeId() + "资格到期停运，学生" + student.studentNo()
                            + "已改派至线路" + targetRouteId;
                    notificationRepository.insertNotification("REASSIGNMENT", student.id(),
                            q.routeId(), today, content);
                    notificationCount++;
                }
            }
        }
        return new ExpireResponse(expiredIds, suspendedRouteIds, reassignmentCount,
                notificationCount);
    }
}
