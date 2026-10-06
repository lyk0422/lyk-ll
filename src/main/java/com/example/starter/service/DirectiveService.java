package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.example.starter.domain.DirectiveRevision;
import com.example.starter.domain.PowerDirective;
import com.example.starter.domain.RetestRecord;
import com.example.starter.domain.Transmitter;
import com.example.starter.repo.DirectiveRepository;
import com.example.starter.repo.EventRepository;
import com.example.starter.repo.RetestRepository;
import com.example.starter.repo.TransmitterRepository;
import com.example.starter.web.dto.LiftRequest;
import com.example.starter.web.dto.LiftResponse;
import com.example.starter.web.dto.PowerView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.example.starter.error.ApiException.badRequest;
import static com.example.starter.error.ApiException.conflict;
import static com.example.starter.error.ApiException.notFound;
import static com.example.starter.error.ApiException.unprocessable;

/**
 * 限功率指令：解除（复测与解除原子提交、双人确认）与轨迹查询。
 */
@Service
public class DirectiveService {

    private final DirectiveRepository directiveRepo;
    private final EventRepository eventRepo;
    private final RetestRepository retestRepo;
    private final TransmitterRepository transmitterRepo;
    private final Clock clock;

    public DirectiveService(DirectiveRepository directiveRepo, EventRepository eventRepo,
                            RetestRepository retestRepo, TransmitterRepository transmitterRepo, Clock clock) {
        this.directiveRepo = directiveRepo;
        this.eventRepo = eventRepo;
        this.retestRepo = retestRepo;
        this.transmitterRepo = transmitterRepo;
        this.clock = clock;
    }

    /**
     * 解除限功率：全部事件结案、复测实测功率不超过限值、运行与监测两名不同人员确认，
     * 复测记录与解除在同一事务原子提交。
     */
    @Transactional
    public LiftResponse lift(long transmitterId, LiftRequest req) {
        Transmitter transmitter = transmitterRepo.findById(transmitterId)
                .orElseThrow(() -> notFound("发射机不存在: " + transmitterId));
        if (req.opsConfirmer().equals(req.monitorConfirmer())) {
            throw badRequest("解除限功率须运行与监测两名不同人员确认");
        }
        // 与初查保持一致的加锁顺序（先发射机后指令），避免并发死锁
        transmitterRepo.lockById(transmitterId);
        PowerDirective directive = directiveRepo.findExecutingForUpdate(transmitterId)
                .orElseThrow(() -> conflict("该发射机无执行中的限功率指令"));
        int unresolved = eventRepo.countUnresolvedByTransmitter(transmitterId);
        if (unresolved > 0) {
            throw conflict("存在 " + unresolved + " 件未结事件，不得解除限功率");
        }
        if (req.actualPowerW() > directive.currentLimitW()) {
            throw unprocessable("实测功率 " + req.actualPowerW() + "W 超过限值 "
                    + directive.currentLimitW() + "W，拒绝确认");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime measuredAt = req.measuredAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        long retestId = retestRepo.insert(transmitterId, directive.id(), req.actualPowerW(),
                directive.currentLimitW(), RetestRecord.RESULT_CLEAR, measuredAt, req.recordedBy(), now);
        directiveRepo.lift(directive.id(), now, req.opsConfirmer(), req.monitorConfirmer());
        directiveRepo.insertRevision(directive.id(), null, DirectiveRevision.TYPE_LIFT,
                transmitter.fullPowerW(), now, "复测无干扰且双人确认，解除限功率");
        return new LiftResponse(directive.id(), retestId, PowerDirective.STATUS_LIFTED, transmitter.fullPowerW());
    }

    /**
     * 查询发射机当前功率状态与全部指令轨迹。
     */
    @Transactional(readOnly = true)
    public PowerView powerView(long transmitterId) {
        transmitterRepo.findById(transmitterId)
                .orElseThrow(() -> notFound("发射机不存在: " + transmitterId));
        var directive = directiveRepo.findExecuting(transmitterId)
                .or(() -> directiveRepo.findLatest(transmitterId));
        List<DirectiveRevision> revisions = directive
                .map(d -> directiveRepo.findRevisions(d.id()))
                .orElse(List.of());
        return new PowerView(directive.orElse(null), revisions,
                eventRepo.countUnresolvedByTransmitter(transmitterId));
    }
}
