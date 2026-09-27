package com.example.starter.service;

import com.example.starter.domain.EotBindingStatus;
import com.example.starter.domain.JobStatus;
import com.example.starter.repo.CarRow;
import com.example.starter.repo.EotBindingRepository;
import com.example.starter.repo.EotBindingRow;
import com.example.starter.repo.EotConfirmationRepository;
import com.example.starter.repo.EotConfirmationRow;
import com.example.starter.repo.EotDeviceRepository;
import com.example.starter.repo.EotDeviceRow;
import com.example.starter.repo.JobRow;
import com.example.starter.repo.TrainCarRepository;
import com.example.starter.repo.TrainJobRepository;
import com.example.starter.web.dto.EotBindingEvidence;
import com.example.starter.web.dto.EotBindingResponse;
import com.example.starter.web.dto.EotConfirmRequest;
import com.example.starter.web.dto.EotConfirmationEvidence;
import com.example.starter.web.dto.EotConfirmationResponse;
import com.example.starter.web.dto.EotDeviceEvidence;
import com.example.starter.web.dto.EotDeviceRegisterRequest;
import com.example.starter.web.dto.EotDeviceResponse;
import com.example.starter.web.dto.EotUnbindRequest;
import com.example.starter.web.dto.EotUnbindResponse;
import com.example.starter.web.dto.EotBindRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 列尾装置核对核心服务。
 * 建档 → 受控作业绑定空闲装置（冻结尾车与编组版本） → 双人一致性共同确认 →
 * 发车确认事务内联动校验 → 销记原子释放；亦可发车前提前解绑并撤销确认。
 * 未绑定列尾装置的历史作业不增加新门禁。
 */
@Service
public class EotService {

    private final EotDeviceRepository deviceRepository;
    private final EotBindingRepository bindingRepository;
    private final EotConfirmationRepository confirmationRepository;
    private final TrainJobRepository jobRepository;
    private final TrainCarRepository carRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public EotService(EotDeviceRepository deviceRepository, EotBindingRepository bindingRepository,
                      EotConfirmationRepository confirmationRepository, TrainJobRepository jobRepository,
                      TrainCarRepository carRepository, IdempotencyService idempotencyService, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.bindingRepository = bindingRepository;
        this.confirmationRepository = confirmationRepository;
        this.jobRepository = jobRepository;
        this.carRepository = carRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 列尾装置建档（合成数据）：编号唯一，初始版本 1、空闲。
     */
    public EotDeviceResponse register(EotDeviceRegisterRequest request) {
        return idempotencyService.execute(request.requestId(), "EOT_DEVICE_REGISTER", request,
                EotDeviceResponse.class, () -> doRegister(request));
    }

    /**
     * 受控作业绑定一台空闲列尾装置，冻结当前编组尾车与编组版本。
     * UTC 下 now &lt; 自检有效截止方可绑定，恰到截止即失效；编号未知、自检不合格或已占用拒绝。
     */
    public EotBindingResponse bind(long jobId, EotBindRequest request) {
        return idempotencyService.execute(request.requestId(), "EOT_BIND",
                new BindParams(jobId, request), EotBindingResponse.class,
                () -> doBind(jobId, request));
    }

    /**
     * 两名不同人员共同确认列尾与最新编组尾车、版本一致；成功后装置版本加 1。
     */
    public EotConfirmationResponse confirm(long jobId, EotConfirmRequest request) {
        return idempotencyService.execute(request.requestId(), "EOT_CONFIRM",
                new ConfirmParams(jobId, request), EotConfirmationResponse.class,
                () -> doConfirm(jobId, request));
    }

    /**
     * 发车前提前解绑：原子解除装置占用并撤销全部一致性确认，绑定历史保留；成功后装置版本加 1。
     */
    public EotUnbindResponse unbind(long jobId, EotUnbindRequest request) {
        return idempotencyService.execute(request.requestId(), "EOT_UNBIND",
                new UnbindParams(jobId, request), EotUnbindResponse.class,
                () -> doUnbind(jobId, request));
    }

    private EotDeviceResponse doRegister(EotDeviceRegisterRequest request) {
        long deviceId;
        try {
            deviceId = deviceRepository.insert(request.deviceNo(), request.selfCheckValidUntil());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("列尾装置编号已建档：" + request.deviceNo());
        }
        return toDeviceResponse(deviceRepository.findById(deviceId).orElseThrow());
    }

    private EotBindingResponse doBind(long jobId, EotBindRequest request) {
        // 先锁作业行再条件更新装置行，与摘挂/确认/发车保持统一加锁顺序
        JobRow job = requireJobForUpdate(jobId);
        EotDeviceRow device = deviceRepository.findByDeviceNo(request.deviceNo())
                .orElseThrow(() -> new NotFoundException("列尾装置编号未知：" + request.deviceNo()));
        if (request.expectedVersion() != device.version()) {
            throw new ConflictException("列尾装置版本冲突：期望 " + request.expectedVersion()
                    + "，当前 " + device.version());
        }
        if (!clock.instant().isBefore(device.selfCheckValidUntil())) {
            throw new BusinessException("列尾装置自检不合格或已过有效截止时刻：" + device.selfCheckValidUntil());
        }
        if (device.boundJobId() != null) {
            throw new BusinessException("列尾装置已被作业占用：" + device.boundJobId());
        }
        if (job.status() == JobStatus.DEPARTURE_CONFIRMED || job.status() == JobStatus.CLOSED) {
            throw new BusinessException("当前作业状态不允许绑定列尾装置：" + job.status());
        }
        if (bindingRepository.findActiveByJobId(jobId).isPresent()) {
            throw new BusinessException("作业已绑定列尾装置，不得重复绑定");
        }
        List<CarRow> activeCars = carRepository.findActiveByJobId(jobId);
        if (activeCars.isEmpty()) {
            throw new BusinessException("编组为空，无法确定尾车，不得绑定列尾装置");
        }
        String tailCarNo = activeCars.get(activeCars.size() - 1).carNo();
        // 原子条件占用：空闲且版本匹配时生效；并发绑定唯一成功
        if (!deviceRepository.occupyIfFree(device.id(), jobId, request.expectedVersion())) {
            throw new BusinessException("列尾装置已被占用或版本已变化，绑定失败");
        }
        long bindingId;
        try {
            bindingId = bindingRepository.insertActive(device.id(), device.deviceNo(), jobId,
                    tailCarNo, job.consistVersion(), request.requestId());
        } catch (DuplicateKeyException e) {
            // 同作业并发绑定其他装置等场景：唯一约束兜底，事务回滚后占用一并撤销
            throw new BusinessException("列尾装置绑定冲突，绑定失败");
        }
        EotDeviceRow updated = deviceRepository.findById(device.id()).orElseThrow();
        return new EotBindingResponse(bindingId, updated.id(), updated.deviceNo(), jobId,
                tailCarNo, job.consistVersion(), EotBindingStatus.ACTIVE.name(), updated.version());
    }

    private EotConfirmationResponse doConfirm(long jobId, EotConfirmRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        EotBindingRow binding = bindingRepository.findActiveByJobId(jobId)
                .orElseThrow(() -> new BusinessException("作业未绑定列尾装置，无法确认一致性"));
        if (job.status() == JobStatus.DEPARTURE_CONFIRMED || job.status() == JobStatus.CLOSED) {
            throw new BusinessException("当前作业状态不允许列尾一致性确认：" + job.status());
        }
        EotDeviceRow device = deviceRepository.findById(binding.deviceId()).orElseThrow();
        if (request.expectedVersion() != device.version()) {
            throw new ConflictException("列尾装置版本冲突：期望 " + request.expectedVersion()
                    + "，当前 " + device.version());
        }
        if (request.confirmer1().equals(request.confirmer2())) {
            throw new BusinessException("一致性确认须由两名不同人员共同完成");
        }
        if (request.consistVersion() != job.consistVersion()) {
            throw new BusinessException("确认的编组版本与最新编组不一致：期望 "
                    + job.consistVersion() + "，提交 " + request.consistVersion());
        }
        List<CarRow> activeCars = carRepository.findActiveByJobId(jobId);
        if (activeCars.isEmpty()) {
            throw new BusinessException("编组为空，无法确定尾车");
        }
        String currentTailCarNo = activeCars.get(activeCars.size() - 1).carNo();
        if (!request.tailCarNo().equals(currentTailCarNo)) {
            throw new BusinessException("确认的尾车与当前编组尾车不一致：当前尾车 " + currentTailCarNo);
        }
        // 乐观版本递增：与摘挂/解绑/并发确认竞争，仅一名成功者提交
        if (!deviceRepository.bumpVersionIfBound(device.id(), jobId, request.expectedVersion())) {
            throw new ConflictException("列尾装置版本已变化，确认失败");
        }
        confirmationRepository.invalidateByBindingId(binding.id());
        long confirmationId = confirmationRepository.insert(binding.id(), device.id(), device.deviceNo(),
                jobId, currentTailCarNo, job.consistVersion(),
                request.confirmer1(), request.confirmer2(), request.requestId());
        EotDeviceRow updated = deviceRepository.findById(device.id()).orElseThrow();
        return new EotConfirmationResponse(confirmationId, binding.id(), device.deviceNo(), jobId,
                currentTailCarNo, job.consistVersion(), request.confirmer1(), request.confirmer2(),
                true, updated.version());
    }

    private EotUnbindResponse doUnbind(long jobId, EotUnbindRequest request) {
        JobRow job = requireJobForUpdate(jobId);
        EotBindingRow binding = bindingRepository.findActiveByJobId(jobId)
                .orElseThrow(() -> new BusinessException("作业无生效列尾绑定，无需解绑"));
        if (job.status() == JobStatus.DEPARTURE_CONFIRMED || job.status() == JobStatus.CLOSED) {
            throw new BusinessException("发车确认后不得提前解绑，列尾装置随销记自动释放：" + job.status());
        }
        // 释放占用与版本递增原子完成，版本不匹配则整体失败
        if (!deviceRepository.releaseIfBoundAndVersion(binding.deviceId(), jobId, request.expectedVersion())) {
            EotDeviceRow current = deviceRepository.findById(binding.deviceId()).orElseThrow();
            throw new ConflictException("列尾装置版本冲突或占用已变化：期望 " + request.expectedVersion()
                    + "，当前 " + current.version());
        }
        int voided = confirmationRepository.invalidateByBindingId(binding.id());
        Instant now = clock.instant();
        if (!bindingRepository.release(binding.id(), now)) {
            throw new ConflictException("绑定状态已变化，解绑失败");
        }
        EotDeviceRow updated = deviceRepository.findById(binding.deviceId()).orElseThrow();
        return new EotUnbindResponse(binding.id(), updated.id(), updated.deviceNo(), jobId,
                true, voided, updated.version());
    }

    /**
     * 摘挂变更同事务联动：绑定保留，但一致性确认立即失效，须重新检查并双人确认。
     */
    public void invalidateConfirmationsOnConsistChange(long jobId) {
        confirmationRepository.invalidateByJobId(jobId);
    }

    /**
     * 销记同事务联动：原子解除装置占用、解除绑定并失效全部确认，绑定历史保留。
     */
    public void releaseOnClosure(long jobId) {
        bindingRepository.findActiveByJobId(jobId).ifPresent(binding -> {
            deviceRepository.releaseIfBound(binding.deviceId(), jobId);
            confirmationRepository.invalidateByBindingId(binding.id());
            bindingRepository.release(binding.id(), clock.instant());
        });
    }

    /**
     * 发车确认事务内联动校验（未启用列尾控制的作业直接放行，不增加门禁）：
     * 装置自检 UTC 下未过期（恰到截止失效）、存在有效一致性确认且确认的尾车与版本匹配最新编组。
     */
    public void assertDepartureReady(JobRow job) {
        bindingRepository.findActiveByJobId(job.id()).ifPresent(binding -> {
            EotDeviceRow device = deviceRepository.findById(binding.deviceId())
                    .orElseThrow(() -> new BusinessException("列尾装置档案缺失：" + binding.deviceNo()));
            if (device.boundJobId() == null || device.boundJobId() != job.id()) {
                throw new BusinessException("列尾装置占用与绑定不一致，拒绝发车");
            }
            if (!clock.instant().isBefore(device.selfCheckValidUntil())) {
                throw new BusinessException("列尾装置自检已过有效截止时刻，拒绝发车："
                        + device.selfCheckValidUntil());
            }
            EotConfirmationRow confirmation = confirmationRepository.findValidByBindingId(binding.id())
                    .orElseThrow(() -> new BusinessException("列尾与编组一致性未共同确认，拒绝发车"));
            if (confirmation.consistVersion() != job.consistVersion()) {
                throw new BusinessException("列尾一致性确认的编组版本已过期，拒绝发车：确认版本 "
                        + confirmation.consistVersion() + "，最新版本 " + job.consistVersion());
            }
            List<CarRow> activeCars = carRepository.findActiveByJobId(job.id());
            if (activeCars.isEmpty()) {
                throw new BusinessException("编组为空，无法核对列尾尾车");
            }
            String currentTailCarNo = activeCars.get(activeCars.size() - 1).carNo();
            if (!confirmation.tailCarNo().equals(currentTailCarNo)) {
                throw new BusinessException("列尾一致性确认的尾车与最新编组尾车不一致，拒绝发车：确认尾车 "
                        + confirmation.tailCarNo() + "，当前尾车 " + currentTailCarNo);
            }
        });
    }

    /**
     * 查询装置完整证据：档案、占用状态与全部绑定历史（含双人确认证据）。
     */
    public EotDeviceEvidence getDeviceEvidence(String deviceNo) {
        EotDeviceRow device = deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> new NotFoundException("列尾装置编号未知：" + deviceNo));
        List<EotBindingRow> bindings = bindingRepository.findAllByDeviceId(device.id());
        Map<Long, List<EotConfirmationRow>> confirmations = loadConfirmations(bindings);
        List<EotBindingEvidence> bindingEvidences = bindings.stream()
                .map(b -> toBindingEvidence(b, confirmations.getOrDefault(b.id(), List.of())))
                .toList();
        return new EotDeviceEvidence(device.id(), device.deviceNo(), device.selfCheckValidUntil(),
                device.boundJobId(), device.version(), bindingEvidences);
    }

    /**
     * 查询作业的列尾绑定与确认证据；未启用列尾控制的作业返回空绑定历史。
     */
    public List<EotBindingEvidence> getJobEvidence(long jobId) {
        requireJob(jobId);
        List<EotBindingRow> bindings = bindingRepository.findAllByJobId(jobId);
        Map<Long, List<EotConfirmationRow>> confirmations = loadConfirmations(bindings);
        return bindings.stream()
                .map(b -> toBindingEvidence(b, confirmations.getOrDefault(b.id(), List.of())))
                .toList();
    }

    private Map<Long, List<EotConfirmationRow>> loadConfirmations(List<EotBindingRow> bindings) {
        List<Long> bindingIds = bindings.stream().map(EotBindingRow::id).toList();
        return confirmationRepository.findAllByBindingIds(bindingIds).stream()
                .collect(Collectors.groupingBy(EotConfirmationRow::bindingId));
    }

    private EotBindingEvidence toBindingEvidence(EotBindingRow binding, List<EotConfirmationRow> rows) {
        List<EotConfirmationEvidence> confirmations = new ArrayList<>();
        for (EotConfirmationRow row : rows) {
            confirmations.add(new EotConfirmationEvidence(row.id(), row.bindingId(), row.deviceNo(),
                    row.tailCarNo(), row.consistVersion(), row.confirmer1(), row.confirmer2(),
                    row.valid(), row.requestId(), row.createdAt()));
        }
        return new EotBindingEvidence(binding.id(), binding.deviceId(), binding.deviceNo(),
                binding.tailCarNo(), binding.consistVersion(), binding.status().name(),
                binding.requestId(), binding.createdAt(), binding.releasedAt(), confirmations);
    }

    private JobRow requireJob(long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
    }

    private JobRow requireJobForUpdate(long jobId) {
        return jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
    }

    private EotDeviceResponse toDeviceResponse(EotDeviceRow device) {
        return new EotDeviceResponse(device.id(), device.deviceNo(), device.selfCheckValidUntil(),
                device.boundJobId(), device.version(), device.createdAt());
    }

    /** 绑定幂等指纹参数：路径作业ID与请求体共同结构化参与指纹。 */
    private record BindParams(long jobId, EotBindRequest request) {
    }

    /** 确认幂等指纹参数：路径作业ID与请求体共同结构化参与指纹。 */
    private record ConfirmParams(long jobId, EotConfirmRequest request) {
    }

    /** 解绑幂等指纹参数：路径作业ID与请求体共同结构化参与指纹。 */
    private record UnbindParams(long jobId, EotUnbindRequest request) {
    }
}
