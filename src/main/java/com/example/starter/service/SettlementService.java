package com.example.starter.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.repo.ManifestRepository;

/**
 * 日结汇总：按品类汇总当日进场（进）与回执（出）数量，由联单与回执明细实时复算，
 * 不落地冗余汇总表，重复查询结果稳定。
 */
@Service
public class SettlementService {

    /** 日结归日时区：业务口径为 Asia/Shanghai。 */
    static final ZoneId LEDGER_ZONE = ZoneId.of("Asia/Shanghai");

    private final ManifestRepository manifestRepository;

    public SettlementService(ManifestRepository manifestRepository) {
        this.manifestRepository = manifestRepository;
    }

    @Transactional(readOnly = true)
    public List<ManifestRepository.SettlementRow> daily(LocalDate date) {
        Instant dayStart = date.atStartOfDay(LEDGER_ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(LEDGER_ZONE).toInstant();
        return manifestRepository.summarizeDaily(dayStart, dayEnd);
    }
}
