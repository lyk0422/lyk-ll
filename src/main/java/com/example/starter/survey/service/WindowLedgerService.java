package com.example.starter.survey.service;

import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.data.WindowLedgerRepository;
import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyType;
import com.example.starter.survey.domain.WindowLedgerRow;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.support.SurveyWindowCalculator;
import com.example.starter.survey.support.SurveyWindowCalculator.SurveyWindow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 窗口台账复算：以船舶基准日与实际检验/展期记录为唯一事实来源，
 * 清空后按 {@link SurveyWindowCalculator} 规则整体重建，结果可复算、可核对。
 */
@Service
public class WindowLedgerService {

    private final WindowLedgerRepository ledgerRepository;
    private final ShipRepository shipRepository;
    private final SurveyRepository surveyRepository;
    private final IdempotentExecutor executor;

    public WindowLedgerService(WindowLedgerRepository ledgerRepository,
                               ShipRepository shipRepository,
                               SurveyRepository surveyRepository,
                               IdempotentExecutor executor) {
        this.ledgerRepository = ledgerRepository;
        this.shipRepository = shipRepository;
        this.surveyRepository = surveyRepository;
        this.executor = executor;
    }

    /**
     * 复算指定船舶在指定年份邻近年的三类窗口台账。
     *
     * @param around 复算基准日（通常为今天 UTC）
     */
    @Transactional
    public int recomputeForShip(long shipId, LocalDate around) {
        Ship ship = shipRepository.findById(shipId).orElseThrow();
        Instant now = executor.now();
        int count = 0;
        for (SurveyType type : SurveyType.values()) {
            for (SurveyWindow window : SurveyWindowCalculator.candidateWindows(ship, type, around)) {
                List<Survey> surveys = surveyRepository.findByShip(shipId).stream()
                        .filter(s -> s.type() == type
                                && s.cycleAnchor().equals(window.anchor()))
                        .toList();
                if (surveys.isEmpty()) {
                    ledgerRepository.insert(shipId, type, window.anchor(),
                            window.start(), window.end(), null, null, now);
                    count++;
                    continue;
                }
                for (Survey survey : surveys) {
                    // 展期末日取检验窗口经批准展期后的实际末日（与证书有效期是两条独立记录）。
                    ledgerRepository.insert(shipId, type, window.anchor(),
                            window.start(), window.end(),
                            survey.extensionUntil(), survey.id(), now);
                    count++;
                }
            }
        }
        return count;
    }

    /** 清空并按今天复算全部船舶台账。 */
    @Transactional
    public int recomputeAll() {
        ledgerRepository.deleteAll();
        LocalDate today = LocalDate.ofInstant(executor.now(), java.time.ZoneOffset.UTC);
        int total = 0;
        for (Ship ship : shipRepository.findAll()) {
            total += recomputeForShip(ship.id(), today);
        }
        return total;
    }

    /** 查看全量台账。 */
    public List<WindowLedgerRow> listAll() {
        return ledgerRepository.findAll();
    }

    /** 查看指定船舶台账。 */
    public List<WindowLedgerRow> listByShip(long shipId) {
        return ledgerRepository.findByShip(shipId);
    }
}
