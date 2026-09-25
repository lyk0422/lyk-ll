package com.example.starter.pump.service;

import com.example.starter.pump.domain.PumpdownEvent;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.PumpdownEventRepository;
import com.example.starter.pump.repo.ReviewArchiveRepository;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.ArchiveResult;
import com.example.starter.pump.web.dto.ReplayResult;
import org.springframework.stereotype.Service;

/**
 * 复盘归档：预降事件终态。归档时复算水位曲线、启停序列、失联派单台账并固化摘要；
 * 重复归档返回首次结果（幂等成功），归档后迟到液位只记迟到事件。
 */
@Service
public class ArchiveService {

    private final PumpdownEventRepository eventRepository;
    private final ReviewArchiveRepository archiveRepository;
    private final ReplayService replayService;

    public ArchiveService(PumpdownEventRepository eventRepository,
                          ReviewArchiveRepository archiveRepository,
                          ReplayService replayService) {
        this.eventRepository = eventRepository;
        this.archiveRepository = archiveRepository;
        this.replayService = replayService;
    }

    /** 首次归档执行体。 */
    public ArchiveResult archive(ArchiveRequest request) {
        PumpdownEvent event = eventRepository.findById(request.pumpdownId())
                .orElseThrow(() -> ApiException.unprocessable("PUMPDOWN_NOT_FOUND")
                        .raise("预降事件不存在: " + request.pumpdownId()));
        if (event.archived()) {
            // 正常不会走到：Facade 已先做幂等重放；此处兜底保证终态语义
            return toResult(event, loadExistingSummary(event.id()), true);
        }
        ReplayResult replay = replayService.replay(event.id());
        String summary = replayService.toSummaryJson(replay);
        archiveRepository.insert(event.id(), request.requestId(), summary, request.archiveTime());
        eventRepository.markArchived(event.id(), request.archiveTime());
        return new ArchiveResult(event.id(), "ARCHIVED", request.archiveTime(), summary, false);
    }

    /** 重复归档幂等返回首次归档结果。 */
    public ArchiveResult replayExisting(PumpdownEvent event) {
        return toResult(event, loadExistingSummary(event.id()), true);
    }

    private String loadExistingSummary(Long pumpdownId) {
        return archiveRepository.findByPumpdownId(pumpdownId)
                .map(ReviewArchiveRepository.ArchiveRecord::summary)
                .orElseThrow(() -> new IllegalStateException("事件已归档但缺少归档记录: " + pumpdownId));
    }

    private ArchiveResult toResult(PumpdownEvent event, String summary, boolean replayed) {
        return new ArchiveResult(event.id(), "ARCHIVED", event.archivedAt(), summary, replayed);
    }
}
