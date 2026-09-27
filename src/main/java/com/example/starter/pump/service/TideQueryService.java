package com.example.starter.pump.service;

import com.example.starter.pump.domain.TideSwitchCandidate;
import com.example.starter.pump.domain.TideZoneConfig;
import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.repo.CatalogRepository;
import com.example.starter.pump.repo.TideConfigRepository;
import com.example.starter.pump.repo.TideReadingRepository;
import com.example.starter.pump.repo.TideSwitchRepository;
import com.example.starter.pump.web.dto.TideCandidateView;
import com.example.starter.pump.web.dto.TideConfigView;
import com.example.starter.pump.web.dto.TideLevelView;
import com.example.starter.pump.web.dto.TideTrailView;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.List;

/**
 * 潮汐查询：潮位曲线与迟到证据、候选确认、工况轨迹、分区配置。
 */
@Service
public class TideQueryService {

    private final CatalogRepository catalogRepository;
    private final TideConfigRepository configRepository;
    private final TideReadingRepository readingRepository;
    private final TideSwitchRepository switchRepository;

    public TideQueryService(CatalogRepository catalogRepository,
                            TideConfigRepository configRepository,
                            TideReadingRepository readingRepository,
                            TideSwitchRepository switchRepository) {
        this.catalogRepository = catalogRepository;
        this.configRepository = configRepository;
        this.readingRepository = readingRepository;
        this.switchRepository = switchRepository;
    }

    /** 分区潮汐配置。 */
    public TideConfigView configView(String zoneCode) {
        TideZoneConfig config = configRepository.find(zoneCode)
                .orElseThrow(() -> ApiException.notFound("TIDE_CONFIG_NOT_FOUND")
                        .raise("分区 " + zoneCode + " 未配置潮汐控制"));
        return new TideConfigView(config.zoneCode(), config.backwaterThreshold(),
                config.currentMode(), config.version(), config.updatedAt());
    }

    /** 分区潮位曲线与迟到证据。 */
    public TideLevelView levelView(String zoneCode) {
        requireZone(zoneCode);
        List<TideLevelView.Reading> readings = readingRepository.loadReadings(zoneCode).stream()
                .map(p -> new TideLevelView.Reading(p.id(),
                        p.observedAtUtc().toInstant(ZoneOffset.UTC), p.tideLevel(), p.receivedAt()))
                .toList();
        List<TideLevelView.LateEvent> lateEvents = readingRepository.loadLateEvents(zoneCode)
                .stream()
                .map(p -> new TideLevelView.LateEvent(p.id(),
                        p.observedAtUtc().toInstant(ZoneOffset.UTC), p.tideLevel(), p.receivedAt(),
                        p.reason()))
                .toList();
        return new TideLevelView(zoneCode, readings, lateEvents);
    }

    /** 单个候选确认详情。 */
    public TideCandidateView candidateView(Long candidateId) {
        TideSwitchCandidate candidate = switchRepository.findCandidate(candidateId)
                .orElseThrow(() -> ApiException.notFound("CANDIDATE_NOT_FOUND")
                        .raise("切换候选不存在: " + candidateId));
        return toView(candidate);
    }

    /** 分区全部候选确认。 */
    public List<TideCandidateView> candidatesOfZone(String zoneCode) {
        requireZone(zoneCode);
        return switchRepository.loadCandidatesByZone(zoneCode).stream()
                .map(this::toView)
                .toList();
    }

    /** 分区工况轨迹。 */
    public TideTrailView trailView(String zoneCode) {
        requireZone(zoneCode);
        List<TideTrailView.TrailItem> items = switchRepository.loadTrail(zoneCode).stream()
                .map(t -> new TideTrailView.TrailItem(t.id(), t.fromMode(), t.toMode(),
                        t.version(), t.candidateId(), t.requestId(), t.switchedAt()))
                .toList();
        return new TideTrailView(zoneCode, items);
    }

    private TideCandidateView toView(TideSwitchCandidate candidate) {
        List<TideCandidateView.StationTarget> stations = switchRepository
                .loadCandidateStations(candidate.id()).stream()
                .map(s -> new TideCandidateView.StationTarget(s.stationCode(), s.targetOpening()))
                .toList();
        return new TideCandidateView(candidate.id(), candidate.zoneCode(), candidate.targetMode(),
                candidate.status(), candidate.tideLevel(),
                candidate.tideObservedAt().toInstant(ZoneOffset.UTC),
                candidate.thresholdSnapshot(), candidate.baseVersion(), candidate.createdAt(),
                candidate.activatedAt(), stations);
    }

    private void requireZone(String zoneCode) {
        if (!catalogRepository.zoneExists(zoneCode)) {
            throw ApiException.notFound("ZONE_NOT_FOUND")
                    .raise("流域分区不存在: " + zoneCode);
        }
    }
}
