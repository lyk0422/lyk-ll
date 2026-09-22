package com.example.starter.sensor.service;

import com.example.starter.sensor.SyncProperties;
import com.example.starter.sensor.api.CorrectionRequest;
import com.example.starter.sensor.api.CorrectionResponse;
import com.example.starter.sensor.api.FrameInputView;
import com.example.starter.sensor.api.FrameView;
import com.example.starter.sensor.api.IngestMessageRequest;
import com.example.starter.sensor.api.IngestMessageResponse;
import com.example.starter.sensor.domain.FrameFailReason;
import com.example.starter.sensor.domain.FrameStatus;
import com.example.starter.sensor.domain.IngestStatus;
import com.example.starter.sensor.domain.SensorType;
import com.example.starter.sensor.engine.PairingEngine;
import com.example.starter.sensor.po.CorrectionRow;
import com.example.starter.sensor.po.DroppedRow;
import com.example.starter.sensor.po.FrameInputRow;
import com.example.starter.sensor.po.FrameRow;
import com.example.starter.sensor.po.MessageRow;
import com.example.starter.sensor.repo.SyncRepository;
import com.example.starter.sensor.time.ServiceClock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * 传感器消息摄取、漂移热更新与融合帧构建的应用服务。
 *
 * <p>语义要点：
 * <ul>
 *     <li>水位线按校正后事件时戳维护；早于「水位线 - 迟到容忍」的消息记 LATE 并只入丢弃表，
 *     不进入事件表，迟到/重复消息永远无法改变已提交帧；</li>
 *     <li>容忍窗口内的乱序消息正常接受，帧构建是事件的确定性函数，与到达顺序无关；</li>
 *     <li>校正参数热更新带版本与生效时刻，仅影响之后构建的未提交候选；</li>
 *     <li>融合帧与目标时刻一一对应：COMMITTED 永久不可变；WAITING 记录可重试，
 *     缺帧/超窗/无法插值的 REJECTED 为终态稳定错误。</li>
 * </ul>
 *
 * <p>并发：公开方法在实例锁内执行，并在锁内通过 {@link TransactionTemplate} 开启与提交事务，
 * 保证「重复检测/水位线判定 → 写入 → 提交」整体串行化，避免并发下读到未提交数据。
 */
@Service
public class SyncService {

    private static final SensorType[] REQUIRED_SENSORS = SensorType.values();

    private final SyncRepository repository;
    private final SyncProperties properties;
    private final ServiceClock clock;
    private final TransactionTemplate transactionTemplate;

    public SyncService(SyncRepository repository,
                       SyncProperties properties,
                       ServiceClock clock,
                       org.springframework.transaction.PlatformTransactionManager txManager) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 摄取一条传感器消息。
     */
    public IngestMessageResponse ingest(SensorType sensor, IngestMessageRequest request) {
        synchronized (this) {
            return transactionTemplate.execute(status -> doIngest(sensor, request));
        }
    }

    private IngestMessageResponse doIngest(SensorType sensor, IngestMessageRequest request) {
        long now = clock.nowMillis();

        // 重复优先：消息 ID 已接受过则只记录丢弃，不改变任何既有事件与帧
        if (repository.existsMessage(sensor.name(), request.messageId())) {
            String detail = "传感器 " + sensor + " 消息 ID " + request.messageId()
                    + " 重复，原始时戳 " + request.rawTimestamp() + " 已被接受，本次摄取被忽略";
            repository.insertDropped(new DroppedRow(0L, sensor.name(), request.messageId(),
                    request.rawTimestamp(), now, IngestStatus.DUPLICATE.name(), detail));
            return new IngestMessageResponse(IngestStatus.DUPLICATE, sensor.name(),
                    request.messageId(), request.rawTimestamp(), null,
                    watermarkOf(sensor), IngestStatus.DUPLICATE.name());
        }

        long watermarkBefore = watermarkOf(sensor);
        PairingEngine.Correction correction =
                PairingEngine.selectCorrection(repository.findCorrections(sensor.name()),
                        request.rawTimestamp());
        long correctedTs = PairingEngine.correct(request.rawTimestamp(), correction);

        long lateness = windowOf(sensor).getLatenessNanos();
        if (watermarkBefore != Long.MIN_VALUE
                && correctedTs < watermarkBefore - lateness) {
            String detail = "传感器 " + sensor + " 消息 " + request.messageId()
                    + " 校正时戳 " + correctedTs + " 早于水位线 " + watermarkBefore
                    + " 超过迟到容忍 " + lateness + "ns，判定迟到丢弃，不参与任何融合帧";
            repository.insertDropped(new DroppedRow(0L, sensor.name(), request.messageId(),
                    request.rawTimestamp(), now, IngestStatus.LATE.name(), detail));
            return new IngestMessageResponse(IngestStatus.LATE, sensor.name(),
                    request.messageId(), request.rawTimestamp(), correctedTs,
                    watermarkBefore, IngestStatus.LATE.name());
        }

        repository.insertMessage(new MessageRow(sensor.name(), request.messageId(),
                request.rawTimestamp(), request.payload(), now));
        long watermarkAfter = Math.max(watermarkBefore, correctedTs);
        return new IngestMessageResponse(IngestStatus.ACCEPTED, sensor.name(),
                request.messageId(), request.rawTimestamp(), correctedTs,
                watermarkAfter, null);
    }

    /**
     * 热更新漂移校正参数。新版本仅对之后构建、且目标时刻 &gt;= effectiveAt 的候选生效。
     */
    public CorrectionResponse updateCorrection(SensorType sensor, CorrectionRequest request) {
        if (request.slope() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "slope 必须为正数，当前值 " + request.slope());
        }
        synchronized (this) {
            return transactionTemplate.execute(status -> {
                long version = repository.nextCorrectionVersion(sensor.name());
                long now = clock.nowMillis();
                CorrectionRow row = new CorrectionRow(0L, sensor.name(), version,
                        request.slope(), request.offsetNanos(), request.effectiveAt(), now);
                long id = repository.insertCorrection(row);
                return new CorrectionResponse(id, sensor.name(), version,
                        request.slope(), request.offsetNanos(), request.effectiveAt());
            });
        }
    }

    /** 查询某传感器的校正参数版本链（按生效时刻排序）。 */
    public List<CorrectionResponse> listCorrections(SensorType sensor) {
        return repository.findCorrections(sensor.name()).stream()
                .map(c -> new CorrectionResponse(c.id(), c.sensor(), c.version(),
                        c.slope(), c.offsetNanos(), c.effectiveAt()))
                .toList();
    }

    /**
     * 构建或取回目标时刻的融合帧。
     *
     * @param enforceWatermark true 时水位线未越过窗口右缘返回 WAITING（可稍后重试）；
     *                         false 时跳过水位线门控，直接给出确定性判定
     */
    public FrameView buildFrame(long targetTimestamp, boolean enforceWatermark) {
        synchronized (this) {
            return transactionTemplate.execute(
                    status -> doBuildFrame(targetTimestamp, enforceWatermark));
        }
    }

    private FrameView doBuildFrame(long targetTimestamp, boolean enforceWatermark) {
        String frameId = frameIdOf(targetTimestamp);
        FrameRow existing = repository.findFrame(frameId);
        if (existing != null) {
            if (!FrameStatus.REJECTED.name().equals(existing.status())
                    || !FrameFailReason.WAITING_FOR_WATERMARK.name().equals(existing.reason())) {
                return loadView(existing);
            }
            // WAITING 记录允许重试：清除后重新判定
            repository.deleteFrame(frameId);
        }

        long now = clock.nowMillis();

        if (enforceWatermark) {
            for (SensorType sensor : REQUIRED_SENSORS) {
                long wm = watermarkOf(sensor);
                long edge = targetTimestamp + windowOf(sensor).getWindowNanos();
                if (wm < edge) {
                    String detail = "传感器 " + sensor + " 水位线 " + wm
                            + " 尚未越过窗口右缘 " + edge
                            + "（目标时刻 " + targetTimestamp + "），数据可能不完整，可稍后重试";
                    FrameRow row = new FrameRow(frameId, targetTimestamp,
                            FrameStatus.REJECTED.name(), now,
                            FrameFailReason.WAITING_FOR_WATERMARK.name(), detail, now);
                    repository.insertFrame(row);
                    return loadView(row);
                }
            }
        }

        List<PairingEngine.MatchedInput> matched = new ArrayList<>();
        for (SensorType sensor : REQUIRED_SENSORS) {
            PairingEngine.PairResult result = PairingEngine.pair(sensor,
                    repository.findMessages(sensor.name()),
                    repository.findCorrections(sensor.name()),
                    targetTimestamp, windowOf(sensor));
            if (!result.isSuccess()) {
                FrameRow row = new FrameRow(frameId, targetTimestamp,
                        FrameStatus.REJECTED.name(), now,
                        result.reason().name(), result.detail(), now);
                repository.insertFrame(row);
                return loadView(row);
            }
            matched.add(result.input());
        }

        FrameRow committed = new FrameRow(frameId, targetTimestamp,
                FrameStatus.COMMITTED.name(), now, null, null, now);
        repository.insertFrame(committed);
        for (PairingEngine.MatchedInput input : matched) {
            repository.insertFrameInput(new FrameInputRow(null, frameId, input.sensor().name(),
                    input.messageId(), input.rawTs(), input.correctedTs(),
                    input.timeDeltaNanos(), input.interpolated(),
                    String.join(",", input.anchorMessageIds()),
                    input.correctionVersion(), input.payload()));
        }
        return loadView(repository.findFrame(frameId));
    }

    /** 查询已存在的融合帧；不存在返回 404。 */
    public FrameView getOrBuildFrame(long targetTimestamp) {
        FrameRow row = repository.findFrame(frameIdOf(targetTimestamp));
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "目标时刻 " + targetTimestamp + " 的融合帧不存在");
        }
        return loadView(row);
    }

    /**
     * 事件时间水位线：全部已接受消息按各自时戳适用版本校正后的最大校正时戳；
     * 无消息时为 {@link Long#MIN_VALUE}。
     */
    public long watermarkOf(SensorType sensor) {
        List<CorrectionRow> corrections = repository.findCorrections(sensor.name());
        long max = Long.MIN_VALUE;
        for (MessageRow m : repository.findMessages(sensor.name())) {
            PairingEngine.Correction c = PairingEngine.selectCorrection(corrections, m.rawTs());
            max = Math.max(max, PairingEngine.correct(m.rawTs(), c));
        }
        return max;
    }

    private FrameView loadView(FrameRow row) {
        List<FrameInputRow> inputs = FrameStatus.COMMITTED.name().equals(row.status())
                ? repository.findFrameInputs(row.frameId())
                : List.of();
        List<FrameInputView> inputViews = inputs.stream()
                .map(FrameViews::toInputView)
                .toList();
        return new FrameView(row.frameId(), row.targetTs(), row.status(), row.committedAt(),
                inputViews, row.reason(), row.reasonDetail(), false);
    }

    private SyncProperties.SensorWindow windowOf(SensorType sensor) {
        SyncProperties.SensorWindow window =
                properties.getWindows().get(sensor.name().toLowerCase());
        if (window == null) {
            throw new IllegalStateException("缺少传感器 " + sensor + " 的窗口配置 sensor.sync.windows."
                    + sensor.name().toLowerCase());
        }
        return window;
    }

    static String frameIdOf(long targetTimestamp) {
        return "F-" + targetTimestamp;
    }
}
