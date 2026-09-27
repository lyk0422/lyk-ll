package com.example.starter.service;

import com.example.starter.domain.JobStatus;
import com.example.starter.repo.CarRow;
import com.example.starter.repo.JobRow;
import com.example.starter.repo.TailBindingRow;
import com.example.starter.repo.TailConfirmationRow;
import com.example.starter.repo.TailConfirmationRepository;
import com.example.starter.repo.TailDeviceRow;
import com.example.starter.repo.TailBindingRepository;
import com.example.starter.repo.TailDeviceRepository;
import com.example.starter.repo.TrainCarRepository;
import com.example.starter.repo.TrainJobRepository;
import com.example.starter.web.dto.TailBindRequest;
import com.example.starter.web.dto.TailBindResponse;
import com.example.starter.web.dto.TailConfirmRequest;
import com.example.starter.web.dto.TailConfirmResponse;
import com.example.starter.web.dto.TailDeviceRegisterRequest;
import com.example.starter.web.dto.TailDeviceResponse;
import com.example.starter.web.dto.TailEvidenceResponse;
import com.example.starter.web.dto.TailUnbindRequest;
import com.example.starter.web.dto.TailUnbindResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 合成列尾装置控制服务：建档、绑定、双人一致性确认、发车前提前解绑、销记原子释放与证据查询。
 * 全部写操作在 {@link IdempotencyService} 的同一事务内完成（业务、版本与幂等结果同事务）；
 * 未启用列尾控制（无生效绑定）的历史作业不增加新门禁。
 */
@Service
public class TailDeviceService {

    /** 一致性确认失效原因：摘挂改变尾车或编组版本。 */
    public static final String REASON_CONSIST_CHANGED = "CONSIST_CHANGED";
    /** 一致性确认失效原因：被同绑定下更新的共同确认取代。 */
    public static final String REASON_SUPERSEDED = "SUPERSEDED";
    /** 一致性确认失效原因：发车前提前解绑。 */
    public static final String REASON_UNBIND = "UNBIND";
    /** 一致性确认失效原因：销记。 */
    public static final String REASON_CLOSURE = "CLOSURE";

    private final TailDeviceRepository deviceRepository;
    private final TailBindingRepository bindingRepository;
    private final TailConfirmationRepository confirmationRepository;
    private final TrainJobRepository jobRepository;
    private final TrainCarRepository carRepository;
    private final IdempotencyService idempotencyService;
    private final BusinessClock clock;

    public TailDeviceService(TailDeviceRepository deviceRepository,
                             TailBindingRepository bindingRepository,
                             TailConfirmationRepository confirmationRepository,
                             TrainJobRepository jobRepository,
                             TrainCarRepository carRepository,
                             IdempotencyService idempotencyService,
                             BusinessClock clock) {
        this.deviceRepository = deviceRepository;
        this.bindingRepository = bindingRepository;
        this.confirmationRepository = confirmationRepository;
        this.jobRepository = jobRepository;
        this.carRepository = carRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 合成列尾装置建档（编号唯一，初始版本 1、空闲）。
     */
    public TailDeviceResponse register(TailDeviceRegisterRequest request) {
        return idempotencyService.execute(request.requestId(), "TAIL_DEVICE_REGISTER", request,
                TailDeviceResponse.class, () -> doRegister(request));
    }

    /**
     * 受控作业绑定一台空闲且自检合格的装置与当前编组尾车；绑定冻结编组版本与尾车号。
     * 并发绑定同一装置仅唯一成功（行锁 + 占用谓词条件更新双重保证）。
     */
    public TailBindResponse bind(long jobId, TailBindRequest request) {
        return idempotencyService.execute(request.requestId(), "TAIL_BIND", request,
                TailBindResponse.class, () -> doBind(jobId, request));
    }

    /**
     * 摘挂改变尾车或编组版本后重新检查，由两名不同人员共同确认新尾车及版本；成功后装置版本加 1。
     */
    public TailConfirmResponse confirm(long jobId, TailConfirmRequest request) {
        return idempotencyService.execute(request.requestId(), "TAIL_CONFIRM", request,
                TailConfirmResponse.class, () -> doConfirm(jobId, request));
    }

    /**
     * 发车前提前解绑：仅允许发车确认前，原子解除装置占用并同时撤销一致性确认；绑定历史保留。
     */
    public TailUnbindResponse unbind(long jobId, TailUnbindRequest request) {
        return idempotencyService.execute(request.requestId(), "TAIL_UNBIND", request,
                TailUnbindResponse.class, () -> doUnbind(jobId, request));
    }

    /**
     * 证据查询：装置当前视图、全部绑定历史（含已解除）及每条绑定下的全部共同确认。
     */
    @Transactional(readOnly = true)
    public TailEvidenceResponse getEvidence(String deviceNo) {
        TailDeviceRow device = deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> new NotFoundException("列尾装置不存在：" + deviceNo));
        List<TailEvidenceResponse.BindingEvidence> bindings = bindingRepository.findByDeviceId(device.id())
                .stream()
                .map(b -> new TailEvidenceResponse.BindingEvidence(
                        b.id(), b.jobId(), b.tailCarNo(), b.consistVersion(), b.boundAt(),
                        b.releasedAt(), b.releaseType() == null ? null : b.releaseType().name(),
                        b.active(),
                        confirmationRepository.findByBindingId(b.id()).stream()
                                .map(this::toConfirmationEvidence)
                                .toList()))
                .toList();
        return new TailEvidenceResponse(toDeviceResponse(device), bindings);
    }

    private TailDeviceResponse doRegister(TailDeviceRegisterRequest request) {
        long deviceId;
        try {
            deviceId = deviceRepository.insert(request.deviceNo(), request.selfCheckExpiresAt());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("列尾装置编号已建档：" + request.deviceNo());
        }
        return toDeviceResponse(deviceRepository.findById(deviceId).orElseThrow());
    }

    private TailBindResponse doBind(long jobId, TailBindRequest request) {
        JobRow job = jobRepository.lockById(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
        if (job.status() == JobStatus.DEPARTURE_CONFIRMED || job.status() == JobStatus.CLOSED) {
            throw new BusinessException("当前状态不允许绑定列尾装置：" + job.status());
        }
        TailDeviceRow device = deviceRepository.lockById(
                        requireDeviceId(request.deviceNo()))
                .orElseThrow(() -> new NotFoundException("列尾装置不存在：" + request.deviceNo()));
        if (device.version() != request.expectedVersion()) {
            throw new BusinessException("列尾装置版本不匹配：expected=" + request.expectedVersion()
                    + ", actual=" + device.version());
        }
        if (device.boundJobId() != null) {
            throw new BusinessException("列尾装置已被作业占用：" + device.deviceNo());
        }
        requireSelfCheckValid(device, clock.now());
        String tailCarNo = currentTailCarNo(jobId);
        if (!tailCarNo.equals(request.tailCarNo())) {
            throw new BusinessException("绑定尾车与当前编组尾车不一致：expected=" + tailCarNo
                    + ", actual=" + request.tailCarNo());
        }
        if (!request.boundAt().isBefore(device.selfCheckExpiresAt())) {
            throw new BusinessException("绑定时刻已晚于或恰达自检截止时刻，不得绑定");
        }
        // 原子占用：期望版本匹配且空闲才命中；版本加 1。
        if (!deviceRepository.occupy(device.id(), request.expectedVersion(), jobId)) {
            throw new BusinessException("列尾装置已被其他作业占用或版本已变更，绑定失败");
        }
        long bindingId;
        try {
            bindingId = bindingRepository.insert(device.id(), device.deviceNo(), jobId,
                    tailCarNo, job.consistVersion(), request.boundAt());
        } catch (DuplicateKeyException e) {
            throw new BusinessException("作业已存在生效中的列尾绑定，不得重复绑定");
        }
        return new TailBindResponse(bindingId, jobId, device.deviceNo(), tailCarNo,
                job.consistVersion(), request.boundAt(), request.expectedVersion() + 1);
    }

    private TailConfirmResponse doConfirm(long jobId, TailConfirmRequest request) {
        JobRow job = jobRepository.lockById(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
        if (job.status() == JobStatus.DEPARTURE_CONFIRMED || job.status() == JobStatus.CLOSED) {
            throw new BusinessException("发车确认后不得再进行列尾一致性确认：" + job.status());
        }
        TailBindingRow binding = bindingRepository.lockActiveByJobId(jobId)
                .orElseThrow(() -> new BusinessException("作业未绑定生效中的列尾装置，无法确认"));
        TailDeviceRow device = deviceRepository.lockById(binding.deviceId())
                .orElseThrow(() -> new NotFoundException("列尾装置不存在：" + binding.deviceId()));
        if (device.version() != request.expectedVersion()) {
            throw new BusinessException("列尾装置版本不匹配：expected=" + request.expectedVersion()
                    + ", actual=" + device.version());
        }
        requireSelfCheckValid(device, clock.now());
        if (request.confirmer1().equals(request.confirmer2())) {
            throw new BusinessException("一致性确认须由两名不同人员共同完成");
        }
        String tailCarNo = currentTailCarNo(jobId);
        long confirmationId = confirmationRepository.insert(binding.id(), jobId, device.id(),
                tailCarNo, job.consistVersion(), request.confirmer1(), request.confirmer2(),
                request.confirmedAt());
        // 同一绑定下旧的有效确认被新确认取代，保证发车只可能匹配最新共同确认。
        confirmationRepository.invalidateOthersInBinding(binding.id(), confirmationId,
                request.confirmedAt(), REASON_SUPERSEDED);
        if (!deviceRepository.bumpVersion(device.id(), request.expectedVersion())) {
            throw new BusinessException("列尾装置版本已变更，一致性确认失败");
        }
        return new TailConfirmResponse(confirmationId, binding.id(), jobId, device.deviceNo(),
                tailCarNo, job.consistVersion(), request.confirmer1(), request.confirmer2(),
                request.confirmedAt(), request.expectedVersion() + 1);
    }

    private TailUnbindResponse doUnbind(long jobId, TailUnbindRequest request) {
        JobRow job = jobRepository.lockById(jobId)
                .orElseThrow(() -> new NotFoundException("作业不存在：" + jobId));
        if (job.status() == JobStatus.DEPARTURE_CONFIRMED || job.status() == JobStatus.CLOSED) {
            throw new BusinessException("仅发车确认前允许提前解绑，当前状态：" + job.status());
        }
        TailBindingRow binding = bindingRepository.lockActiveByJobId(jobId)
                .orElseThrow(() -> new BusinessException("作业不存在生效中的列尾绑定，无需解绑"));
        TailDeviceRow device = deviceRepository.lockById(binding.deviceId()).orElseThrow();
        if (device.version() != request.expectedVersion()) {
            throw new BusinessException("列尾装置版本不匹配：expected=" + request.expectedVersion()
                    + ", actual=" + device.version());
        }
        int revoked = confirmationRepository.invalidateByBinding(binding.id(),
                request.unboundAt(), REASON_UNBIND);
        if (bindingRepository.release(binding.id(), request.unboundAt(),
                com.example.starter.domain.TailReleaseType.UNBIND) == 0) {
            throw new BusinessException("列尾绑定已被解除，解绑失败");
        }
        if (!deviceRepository.release(device.id(), request.expectedVersion(), jobId)) {
            throw new BusinessException("列尾装置占用状态或版本已变更，解绑失败");
        }
        return new TailUnbindResponse(binding.id(), jobId, device.deviceNo(), request.unboundAt(),
                revoked, request.expectedVersion() + 1);
    }

    /**
     * 销记事务内调用：原子解除装置占用、置绑定解除并撤销确认，绑定历史全部保留。
     * 无生效绑定的历史作业不产生任何影响。
     */
    void releaseOnClosure(long jobId) {
        TailBindingRow binding = bindingRepository.findActiveByJobId(jobId).orElse(null);
        if (binding == null) {
            return;
        }
        Instant now = clock.now();
        confirmationRepository.invalidateByBinding(binding.id(), now, REASON_CLOSURE);
        bindingRepository.release(binding.id(), now, com.example.starter.domain.TailReleaseType.CLOSURE);
        deviceRepository.releaseForClosure(binding.deviceId(), jobId);
    }

    /**
     * 摘挂事务内调用：绑定保留，但该绑定下的一致性确认立即失效，须重新共同确认。
     */
    void invalidateConfirmationOnConsistChanged(long jobId) {
        bindingRepository.findActiveByJobId(jobId).ifPresent(binding ->
                confirmationRepository.invalidateByBinding(binding.id(), clock.now(),
                        REASON_CONSIST_CHANGED));
    }

    /**
     * 发车确认事务内的列尾门禁：无生效绑定的历史作业不设门禁；有绑定则须自检未过期
     * 且存在与最新编组尾车、编组版本完全匹配的有效共同确认。
     */
    void verifyForDeparture(long jobId) {
        // 与绑定/解绑/摘挂一致的加锁顺序：作业 → 绑定 → 装置，避免交叉死锁。
        JobRow job = jobRepository.lockById(jobId).orElseThrow();
        TailBindingRow binding = bindingRepository.lockActiveByJobId(jobId).orElse(null);
        if (binding == null) {
            // 从未启用列尾控制的历史作业不设门禁；受控作业提前解绑后不得在无生效绑定下放行，
            // 须重新绑定并由两名不同人员重新共同确认。
            if (bindingRepository.existsByJobId(jobId)) {
                throw new BusinessException("受控作业当前无生效列尾绑定（已提前解绑），须重新绑定并共同确认后方可发车");
            }
            return;
        }
        TailDeviceRow device = deviceRepository.lockById(binding.deviceId())
                .orElseThrow(() -> new BusinessException("绑定的列尾装置档案缺失"));
        requireSelfCheckValid(device, clock.now());
        TailConfirmationRow confirmation = confirmationRepository.findValidByJobId(jobId)
                .orElseThrow(() -> new BusinessException("列尾装置一致性确认缺失或已失效，不得发车"));
        String tailCarNo = currentTailCarNo(jobId);
        if (confirmation.consistVersion() != job.consistVersion()
                || !confirmation.tailCarNo().equals(tailCarNo)) {
            throw new BusinessException("列尾一致性确认与最新编组（尾车/版本）不匹配，不得使用旧确认放行");
        }
    }

    private long requireDeviceId(String deviceNo) {
        return deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> new BusinessException("列尾装置编号未知：" + deviceNo))
                .id();
    }

    /** 自检合格判定：UTC 下 now 严格早于截止时刻，恰到截止即失效。 */
    private void requireSelfCheckValid(TailDeviceRow device, Instant now) {
        if (!now.isBefore(device.selfCheckExpiresAt())) {
            throw new BusinessException("列尾装置自检不合格或已过有效截止时刻：" + device.deviceNo());
        }
    }

    private String currentTailCarNo(long jobId) {
        List<CarRow> active = carRepository.findActiveByJobId(jobId);
        if (active.isEmpty()) {
            throw new BusinessException("当前编组无在列车辆，无法确定尾车");
        }
        return active.get(active.size() - 1).carNo();
    }

    private TailDeviceResponse toDeviceResponse(TailDeviceRow device) {
        return new TailDeviceResponse(device.deviceNo(), device.selfCheckExpiresAt(), device.version(),
                device.boundJobId(), device.boundJobId() != null);
    }

    private TailEvidenceResponse.ConfirmationEvidence toConfirmationEvidence(TailConfirmationRow c) {
        return new TailEvidenceResponse.ConfirmationEvidence(
                c.id(), c.tailCarNo(), c.consistVersion(), c.confirmer1(), c.confirmer2(),
                c.confirmedAt(), c.valid(), c.invalidatedAt(), c.invalidatedReason());
    }
}
