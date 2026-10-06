package com.example.starter.permit;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import com.example.starter.common.ApiException;
import com.example.starter.common.Fingerprints;
import com.example.starter.common.IdempotencyService;
import com.example.starter.permit.PermitDtos.IssuePermitRequest;
import com.example.starter.permit.PermitDtos.PermitResponse;
import com.example.starter.permit.PermitDtos.ReportResponse;
import com.example.starter.permit.PermitDtos.SubmitReportRequest;
import com.example.starter.task.PruningTaskRepository;
import com.example.starter.task.PruningTaskRepository.TaskRow;
import com.example.starter.warning.WarningRepository;
import org.springframework.stereotype.Service;

/**
 * 作业许可与回传服务：
 * 同一路段同时刻仅允许一张执行中许可（segment_active_permit 主键保证）；
 * 大风预警冻结期间高风险路段禁止签发新的高空作业许可；
 * 回传必须包含前后照片标识与弃枝清运记录，闭环判定含恰第 7 日。
 */
@Service
public class PermitService {

    private final PermitRepository permitRepository;
    private final PruningTaskRepository taskRepository;
    private final WarningRepository warningRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public PermitService(PermitRepository permitRepository, PruningTaskRepository taskRepository,
            WarningRepository warningRepository, IdempotencyService idempotencyService, Clock clock) {
        this.permitRepository = permitRepository;
        this.taskRepository = taskRepository;
        this.warningRepository = warningRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 幂等签发许可。既定时限取自任务当前截止日，后续计划重排不改变在途许可。
     */
    public PermitResponse issue(IssuePermitRequest request) {
        String fingerprint = Fingerprints.of(request.requestId(), request.taskId(), request.workType());
        return idempotencyService.execute(request.requestId(), fingerprint, PermitResponse.class, () -> {
            TaskRow task = taskRepository.findByIdForUpdate(request.taskId())
                    .orElseThrow(() -> ApiException.notFound("修剪任务不存在：" + request.taskId()));
            if (!"PENDING".equals(task.status()) && !"IN_PROGRESS".equals(task.status())) {
                throw ApiException.conflict("任务状态不允许签发许可：" + task.status());
            }
            if ("AERIAL".equals(request.workType()) && warningRepository.isFrozen(task.segmentId())) {
                throw ApiException.conflict("路段处于大风预警冻结状态，禁止签发新的高空作业许可");
            }
            if (permitRepository.segmentOccupied(task.segmentId())) {
                throw ApiException.conflict("同一路段已存在执行中许可");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            String permitNo = "PM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            long permitId = permitRepository.insertPermit(permitNo, task.id(), task.segmentId(),
                    request.workType(), now, task.deadlineDate());
            permitRepository.occupySegment(task.segmentId(), permitId);
            taskRepository.updateStatus(task.id(), "IN_PROGRESS");
            return new PermitResponse(permitId, permitNo, task.id(), task.segmentId(),
                    request.workType(), "EXECUTING", task.deadlineDate());
        });
    }

    /**
     * 幂等回传：校验前后照片标识与弃枝清运记录，闭环任务并释放路段占用；
     * 闭环日晚于截止日（即超过第 7 日）标记逾期升级督办。
     */
    public ReportResponse report(long permitId, SubmitReportRequest request) {
        if (isBlank(request.beforePhotoId()) || isBlank(request.afterPhotoId())
                || isBlank(request.disposalRecord())) {
            throw ApiException.badRequest("作业前照片标识、作业后照片标识与弃枝清运记录不能为空");
        }
        String fingerprint = Fingerprints.of(request.requestId(), permitId, request.beforePhotoId(),
                request.afterPhotoId(), request.disposalRecord());
        return idempotencyService.execute(request.requestId(), fingerprint, ReportResponse.class, () -> {
            var permit = permitRepository.findByIdForUpdate(permitId)
                    .orElseThrow(() -> ApiException.notFound("作业许可不存在：" + permitId));
            if (!"EXECUTING".equals(permit.status())) {
                throw ApiException.conflict("许可状态不允许回传：" + permit.status());
            }
            LocalDateTime now = LocalDateTime.now(clock);
            long reportId = permitRepository.insertReport(permitId, request.beforePhotoId(),
                    request.afterPhotoId(), request.disposalRecord(), now);
            permitRepository.markCompleted(permitId, now);
            permitRepository.releaseSegment(permit.segmentId());

            TaskRow task = taskRepository.findByIdForUpdate(permit.taskId())
                    .orElseThrow(() -> ApiException.notFound("修剪任务不存在：" + permit.taskId()));
            boolean onTime = !LocalDate.now(clock).isAfter(task.deadlineDate());
            taskRepository.markClosed(task.id(), now, !onTime);
            return new ReportResponse(reportId, permitId, task.id(), now, task.deadlineDate(), onTime, !onTime);
        });
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
