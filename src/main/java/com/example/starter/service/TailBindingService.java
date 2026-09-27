package com.example.starter.service;

import com.example.starter.domain.JobStatus;
import com.example.starter.domain.ReleaseReason;
import com.example.starter.repo.CarRow;
import com.example.starter.repo.JobRow;
import com.example.starter.repo.TailBindingRepository;
import com.example.starter.repo.TailBindingRow;
import com.example.starter.repo.TailConfirmationRepository;
import com.example.starter.repo.TailConfirmationRow;
import com.example.starter.repo.TailDeviceRepository;
import com.example.starter.repo.TailDeviceRow;
import com.example.starter.repo.TrainCarRepository;
import com.example.starter.repo.TrainJobRepository;
import com.example.starter.web.dto.TailBindRequest;
import com.example.starter.web.dto.TailBindingHistoryResponse;
import com.example.starter.web.dto.TailBindingResponse;
import com.example.starter.web.dto.TailConfirmRequest;
import com.example.starter.web.dto.TailConfirmationResponse;
import com.example.starter.web.dto.TailUnbindRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 列尾装置绑定与一致性确认服务。
 * 受控作业必须绑定一台空闲装置与当前编组尾车；UTC 下 now&lt;自检有效截止才可绑定，恰达截止失效。
 * 摘挂改变尾车或编组版本时绑定保留但一致性确认立即失效，须两名不同人员重新确认。
 * 销记原子解除装置占用并保留全部绑定历史；提前解绑仅发车前允许并同时撤销确认。
 */
@Service
public class TailBindingService {

    private final TrainJobRepository jobRepository;
    private final TrainCarRepository carRepository;
    private final TailDeviceRepository deviceRepository;
    private final TailBindingRepository bindingRepository;
    private final TailConfirmationRepository confirmationRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public TailBindingService(TrainJobRepository jobRepository, TrainCarRepository carRepository,
                              TailDeviceRepository deviceRepository,
                              TailBindingRepository bindingRepository,
                              TailConfirmationRepository confirmationRepository,
                              IdempotencyService idempotencyService, Clock clock) {
        this.jobRepository = jobRepository;
        this.carRepository = carRepository;
        this.deviceRepository = deviceRepository;
        this.bindingRepository = bindingRepository;
        this.confirmationRepository = confirmationRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 绑定列尾装置与当前编组尾车；冻结编组版本。并发绑定同一装置唯一成功。
     */
    public TailBindingResponse bind(long jobId, TailBindRequest request) {
        return idempotencyService.execute(request.requestId(), "TAIL_BIND", request,
                TailBindingResponse.class, () -> doBind(jobId, request));
    }

    /**
     * 一致性确认：确认人声明当前尾车与编组版本，须与实际一致；同一版本须两名不同人员确认。
     */
    public TailConfirmationResponse confirm(long jobId, TailConfirmRequest request) {
        return idempotencyService.execute(request.requestId(), "TAIL_CONFIRM", request,
                TailConfirmationResponse.class, () -> doConfirm(jobId, request));
    }

    /**
     * 提前解绑：仅发车前允许，原子解除装置占用并同时撤销一致性确认。
     */
    public TailBindingResponse unbind(long jobId, TailUnbindRequest request) {
        return idempotencyService.execute(request.requestId(), "TAIL_UNBIND", request,
                TailBindingResponse.class, () -> doUnbind(jobId, request));
    }

    /**
     * 证据查询：作业全部绑定历史（含已解除）及各自的一致性确认记录。
     */
    @Transactional(readOnly = true)
    public TailBindingHistoryResponse getHistory(long jobId) {
        requireJob(jobId);
        List<TailBindingHistoryResponse.BindingView> bindings = bindingRepository.findByJobId(jobId)
                .stream()
                .map(b -> new TailBindingHistoryResponse.BindingView(b.id(), b.deviceNo(), b.tailCarNo(),
                        b.consistVersion(), b.status().name(),
                        b.releaseReason() == null ? null : b.releaseReason().name(),
                        confirmationRepository.findByBindingId(b.id()).stream()
                                .map(this::toConfirmationResponse).toList()))
                .toList();
        return new TailBindingHistoryResponse(jobId, bindings);
    }

    /**
     * 摘挂变更同事务调用：作业全部有效一致性确认立即失效（绑定保留）。
     */
    public void invalidateConfirmations(long jobId) {
        confirmationRepository.invalidateAllByJobId(jobId);
    }

    /**
     * 销记同事务调用：原子解除装置占用、解除生效中绑定并撤销确认，保留全部历史。
     */
    public void releaseOnClosure(long jobId) {
        bindingRepository.findActiveByJobId(jobId).ifPresent(binding -> {
            deviceRepository.releaseOnClosure(binding.deviceId(), jobId);
            bindingRepository.release(binding.id(), ReleaseReason.CLOSURE);
            confirmationRepository.invalidateAllByJobId(jobId);
        });
    }

    /**
     * 发车确认同事务调用（仅受控作业）：装置自检未过期且一致性确认匹配最新编组（两名不同人员）。
     */
    public void requireDepartureReady(JobRow job) {
        TailBindingRow binding = bindingRepository.findActiveByJobId(job.id())
                .orElseThrow(() -> new BusinessException("受控作业未绑定列尾装置，拒绝发车"));
        TailDeviceRow device = deviceRepository.findById(binding.deviceId())
                .orElseThrow(() -> new IllegalStateException("绑定装置档案缺失：" + binding.deviceId()));
        Instant now = Instant.now(clock);
        if (!now.isBefore(device.selfTestValidUntil())) {
            throw new BusinessException("列尾装置自检已过期（截止 " + device.selfTestValidUntil() + "），拒绝发车");
        }
        List<TailConfirmationRow> confirmations =
                confirmationRepository.findValidByBindingAndVersion(binding.id(), job.consistVersion());
        long confirmers = confirmations.stream().map(TailConfirmationRow::confirmer).distinct().count();
        if (confirmers < 2) {
            throw new BusinessException("列尾一致性未经两名不同人员确认当前编组（版本 "
                    + job.consistVersion() + "），拒绝发车");
        }
    }

    private TailBindingResponse doBind(long jobId, TailBindRequest request) {
        JobRow job = requireJob(jobId);
        if (!job.tailControlEnabled()) {
            throw new BusinessException("作业未启用列尾控制，不得绑定列尾装置");
        }
        if (job.status() != JobStatus.ACCEPTED && job.status() != JobStatus.CHECKED) {
            throw new BusinessException("当前状态不允许绑定列尾装置：" + job.status());
        }
        if (bindingRepository.findActiveByJobId(jobId).isPresent()) {
            throw new BusinessException("作业已存在生效中的列尾绑定，须先解绑");
        }
        TailDeviceRow device = deviceRepository.findByDeviceNo(request.deviceNo())
                .orElseThrow(() -> new NotFoundException("列尾装置不存在：" + request.deviceNo()));
        Instant now = Instant.now(clock);
        if (!now.isBefore(device.selfTestValidUntil())) {
            throw new BusinessException("列尾装置自检不合格或已过期（截止 " + device.selfTestValidUntil() + "）");
        }
        if (device.version() != request.expectedVersion()) {
            throw new ConflictException("装置版本冲突：期望 " + request.expectedVersion()
                    + "，当前 " + device.version());
        }
        if (device.currentJobId() != null) {
            throw new BusinessException("列尾装置已占用：" + request.deviceNo());
        }
        if (!deviceRepository.occupy(request.deviceNo(), request.expectedVersion(), jobId)) {
            // 并发竞争失败：重新查询给出准确原因
            TailDeviceRow latest = deviceRepository.findByDeviceNo(request.deviceNo()).orElseThrow();
            if (latest.version() != request.expectedVersion()) {
                throw new ConflictException("装置版本冲突：期望 " + request.expectedVersion()
                        + "，当前 " + latest.version());
            }
            throw new BusinessException("列尾装置已占用：" + request.deviceNo());
        }
        String tailCarNo = currentTailCarNo(jobId);
        long bindingId = bindingRepository.insert(jobId, device.id(), device.deviceNo(),
                tailCarNo, job.consistVersion(), request.requestId());
        return new TailBindingResponse(bindingId, jobId, device.deviceNo(), tailCarNo,
                job.consistVersion(), "ACTIVE", null);
    }

    private TailConfirmationResponse doConfirm(long jobId, TailConfirmRequest request) {
        JobRow job = requireJob(jobId);
        TailBindingRow binding = bindingRepository.findActiveByJobId(jobId)
                .orElseThrow(() -> new BusinessException("作业无生效中的列尾绑定，不得确认"));
        String tailCarNo = currentTailCarNo(jobId);
        if (request.consistVersion() != job.consistVersion() || !request.tailCarNo().equals(tailCarNo)) {
            throw new BusinessException("确认的尾车或编组版本与当前编组不一致：当前尾车 " + tailCarNo
                    + "，版本 " + job.consistVersion());
        }
        if (confirmationRepository.existsValidConfirmer(binding.id(), job.consistVersion(),
                request.confirmer())) {
            throw new BusinessException("确认人 " + request.confirmer() + " 已确认当前编组版本，须两名不同人员");
        }
        long confirmationId = confirmationRepository.insert(binding.id(), jobId, job.consistVersion(),
                tailCarNo, request.confirmer(), request.requestId());
        return new TailConfirmationResponse(confirmationId, binding.id(), request.confirmer(),
                tailCarNo, job.consistVersion(), true);
    }

    private TailBindingResponse doUnbind(long jobId, TailUnbindRequest request) {
        JobRow job = requireJob(jobId);
        if (job.status() == JobStatus.DEPARTURE_CONFIRMED || job.status() == JobStatus.CLOSED) {
            throw new BusinessException("发车确认后不得提前解绑，须销记解除：" + job.status());
        }
        TailBindingRow binding = bindingRepository.findActiveByJobId(jobId)
                .orElseThrow(() -> new BusinessException("作业无生效中的列尾绑定"));
        TailDeviceRow device = deviceRepository.findById(binding.deviceId()).orElseThrow();
        if (device.version() != request.expectedVersion()) {
            throw new ConflictException("装置版本冲突：期望 " + request.expectedVersion()
                    + "，当前 " + device.version());
        }
        if (!deviceRepository.release(device.deviceNo(), request.expectedVersion(), jobId)) {
            TailDeviceRow latest = deviceRepository.findById(binding.deviceId()).orElseThrow();
            throw new ConflictException("装置版本冲突：期望 " + request.expectedVersion()
                    + "，当前 " + latest.version());
        }
        bindingRepository.release(binding.id(), ReleaseReason.EARLY_UNBIND);
        confirmationRepository.invalidateAllByJobId(jobId);
        return new TailBindingResponse(binding.id(), jobId, binding.deviceNo(), binding.tailCarNo(),
                binding.consistVersion(), "RELEASED", ReleaseReason.EARLY_UNBIND.name());
    }

    private JobRow requireJob(long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
    }

    /**
     * 当前编组尾车车辆号（顺位最大的在列车辆）。
     */
    private String currentTailCarNo(long jobId) {
        List<CarRow> active = carRepository.findActiveByJobId(jobId);
        if (active.isEmpty()) {
            throw new BusinessException("编组为空，无尾车可绑定");
        }
        return active.get(active.size() - 1).carNo();
    }

    private TailConfirmationResponse toConfirmationResponse(TailConfirmationRow row) {
        return new TailConfirmationResponse(row.id(), row.bindingId(), row.confirmer(),
                row.tailCarNo(), row.consistVersion(), row.valid());
    }
}
