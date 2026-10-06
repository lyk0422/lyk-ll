package com.example.starter.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.domain.CheckType;
import com.example.starter.domain.RouteStatus;
import com.example.starter.error.BusinessException;
import com.example.starter.idempotency.IdempotencyService;
import com.example.starter.repo.NotificationRepository;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.StudentRepository;
import com.example.starter.time.TimeProvider;
import com.example.starter.web.dto.RouteDtos.CheckInResponse;
import com.example.starter.web.dto.RouteDtos.MissedBoardingResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学生上下车打卡与漏乘名单服务。
 * 打卡按学生+日期+类型去重；漏乘名单实时生成合成通知记录。
 */
@Service
public class CheckInService {

    private final RouteRepository routeRepository;
    private final StudentRepository studentRepository;
    private final NotificationRepository notificationRepository;
    private final IdempotencyService idempotencyService;
    private final TimeProvider timeProvider;

    public CheckInService(RouteRepository routeRepository,
                          StudentRepository studentRepository,
                          NotificationRepository notificationRepository,
                          IdempotencyService idempotencyService,
                          TimeProvider timeProvider) {
        this.routeRepository = routeRepository;
        this.studentRepository = studentRepository;
        this.notificationRepository = notificationRepository;
        this.idempotencyService = idempotencyService;
        this.timeProvider = timeProvider;
    }

    /**
     * 学生上下车打卡（幂等且按学生+日期+类型去重）。
     */
    @Transactional
    public CheckInResponse checkIn(long routeId, long studentId, String checkType,
                                   String requestId) {
        LocalDate today = timeProvider.today();
        String fingerprint = idempotencyService.fingerprint(requestId, routeId, studentId,
                checkType, today);
        return idempotencyService.execute("POST /api/routes/{id}/check-ins", requestId,
                fingerprint, CheckInResponse.class,
                () -> doCheckIn(routeId, studentId, checkType, requestId, today));
    }

    private CheckInResponse doCheckIn(long routeId, long studentId, String checkType,
                                      String requestId, LocalDate today) {
        CheckType type = parseCheckType(checkType);
        RouteRepository.RouteRow route = routeRepository.findById(routeId)
                .orElseThrow(() -> BusinessException.notFound("线路不存在: " + routeId));
        if (!RouteStatus.IN_PROGRESS.name().equals(route.status())) {
            throw BusinessException.conflict("ROUTE_NOT_IN_PROGRESS",
                    "线路未在执行中，不允许打卡: " + route.status());
        }
        StudentRepository.StudentRow student = studentRepository.findStudentById(studentId)
                .orElseThrow(() -> BusinessException.notFound("学生不存在: " + studentId));
        if (student.routeId() == null || student.routeId() != routeId) {
            throw BusinessException.unprocessable("STUDENT_NOT_ON_ROUTE",
                    "学生不属于该线路: studentId=" + studentId + ", routeId=" + routeId);
        }

        var existing = studentRepository.findCheckIn(studentId, today, type.name());
        if (existing.isPresent()) {
            return toResponse(existing.get(), true);
        }
        try {
            long id = studentRepository.insertCheckIn(studentId, routeId, today, type.name(),
                    requestId);
            return new CheckInResponse(id, studentId, routeId, today, type.name(), false);
        } catch (DuplicateKeyException e) {
            // 并发重复打卡：以数据库唯一约束为准，返回既有记录
            return studentRepository.findCheckIn(studentId, today, type.name())
                    .map(row -> toResponse(row, true))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 生成线路当日漏乘名单（幂等）：未上车打卡的学生实时生成合成通知记录。
     */
    @Transactional
    public MissedBoardingResponse generateMissedBoarding(long routeId, String requestId) {
        LocalDate today = timeProvider.today();
        String fingerprint = idempotencyService.fingerprint(requestId, routeId, today);
        return idempotencyService.execute("POST /api/routes/{id}/missed-boarding", requestId,
                fingerprint, MissedBoardingResponse.class,
                () -> doGenerateMissed(routeId, today));
    }

    private MissedBoardingResponse doGenerateMissed(long routeId, LocalDate today) {
        routeRepository.findById(routeId)
                .orElseThrow(() -> BusinessException.notFound("线路不存在: " + routeId));
        Set<Long> boarded = new HashSet<>(
                studentRepository.findBoardedStudentIds(routeId, today));
        List<Long> missedStudentIds = new ArrayList<>();
        List<Long> notificationIds = new ArrayList<>();
        for (StudentRepository.StudentRow student
                : studentRepository.findStudentsByRoute(routeId)) {
            if (boarded.contains(student.id())) {
                continue;
            }
            missedStudentIds.add(student.id());
            if (!notificationRepository.notificationExists("MISSED_BOARDING", student.id(),
                    routeId, today)) {
                long notificationId = notificationRepository.insertNotification("MISSED_BOARDING",
                        student.id(), routeId, today,
                        "学生" + student.studentNo() + "今日未上车打卡，列入漏乘名单");
                notificationIds.add(notificationId);
            }
        }
        return new MissedBoardingResponse(routeId, today, missedStudentIds, notificationIds);
    }

    private CheckType parseCheckType(String checkType) {
        try {
            return CheckType.valueOf(checkType);
        } catch (IllegalArgumentException e) {
            throw BusinessException.unprocessable("INVALID_CHECK_TYPE",
                    "打卡类型须为 BOARD 或 ALIGHT: " + checkType);
        }
    }

    private CheckInResponse toResponse(StudentRepository.CheckInRow row, boolean deduplicated) {
        return new CheckInResponse(row.id(), row.studentId(), row.routeId(), row.checkDate(),
                row.checkType(), deduplicated);
    }
}
