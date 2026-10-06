package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.domain.RetestResult;
import com.example.starter.error.BusinessException;
import com.example.starter.repository.DirectiveRepository;
import com.example.starter.repository.EventRepository;
import com.example.starter.repository.ReleaseConfirmationRepository;
import com.example.starter.repository.RetestRepository;
import com.example.starter.repository.TransmitterRepository;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;

/**
 * 解除限功率：全部事件结案且复测无干扰后，由运行和监测两名不同人员确认方可解除；
 * 解除与复测记录在同一事务内原子提交。
 */
@Service
public class ReleaseService {

    private final TransmitterRepository transmitterRepository;
    private final EventRepository eventRepository;
    private final DirectiveRepository directiveRepository;
    private final RetestRepository retestRepository;
    private final ReleaseConfirmationRepository releaseConfirmationRepository;
    private final Clock clock;

    public ReleaseService(TransmitterRepository transmitterRepository,
            EventRepository eventRepository,
            DirectiveRepository directiveRepository,
            RetestRepository retestRepository,
            ReleaseConfirmationRepository releaseConfirmationRepository,
            Clock clock) {
        this.transmitterRepository = transmitterRepository;
        this.eventRepository = eventRepository;
        this.directiveRepository = directiveRepository;
        this.retestRepository = retestRepository;
        this.releaseConfirmationRepository = releaseConfirmationRepository;
        this.clock = clock;
    }

    @Transactional
    public ReleaseResponse release(long transmitterId, ReleaseRequest request) {
        transmitterRepository.findById(transmitterId)
                .orElseThrow(() -> BusinessException.notFound("发射机不存在: " + transmitterId));
        if (request.operationStaffId().equals(request.monitoringStaffId())) {
            throw BusinessException.unprocessable("解除限功率须由运行和监测两名不同人员确认");
        }
        if (request.retestResult() != RetestResult.NO_INTERFERENCE) {
            throw BusinessException.unprocessable("复测仍有干扰，不允许解除限功率");
        }
        transmitterRepository.lockById(transmitterId);
        if (eventRepository.existsUnclosedForTransmitter(transmitterId)) {
            throw BusinessException.unprocessable("存在未结案的干扰事件，不允许解除限功率");
        }
        var executing = directiveRepository.findExecutingByTransmitter(transmitterId)
                .orElseThrow(() -> BusinessException.unprocessable("该发射机没有执行中的限功率指令"));

        Instant now = clock.instant();
        long retestId = retestRepository.insert(transmitterId, request.retestResult().name(),
                request.retestSampledAt(), request.retestEvidenceRef(), now);
        directiveRepository.release(executing.id(), now);
        long confirmationId = releaseConfirmationRepository.insert(executing.id(), retestId,
                request.operationStaffId(), request.monitoringStaffId(), now);
        return new ReleaseResponse(executing.id(), retestId, confirmationId, now);
    }
}
