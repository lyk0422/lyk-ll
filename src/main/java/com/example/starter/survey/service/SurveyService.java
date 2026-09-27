package com.example.starter.survey.service;

import com.example.starter.survey.data.ShipRepository;
import com.example.starter.survey.data.SurveyEventRepository;
import com.example.starter.survey.data.SurveyItemCompletionRepository;
import com.example.starter.survey.data.SurveyRepository;
import com.example.starter.survey.data.DefectRepository;
import com.example.starter.survey.domain.Defect;
import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.Survey;
import com.example.starter.survey.domain.SurveyItem;
import com.example.starter.survey.domain.SurveyStatus;
import com.example.starter.survey.domain.SurveyType;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.support.SurveyWindowCalculator;
import com.example.starter.survey.support.SurveyWindowCalculator.SurveyWindow;
import com.example.starter.survey.web.BadRequestException;
import com.example.starter.survey.web.BusinessRuleException;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.ApplySurveyRequest;
import com.example.starter.survey.web.dto.BoardResponse;
import com.example.starter.survey.web.dto.BoardSurveyRequest;
import com.example.starter.survey.web.dto.DefectInput;
import com.example.starter.survey.web.dto.DefectResponse;
import com.example.starter.survey.web.dto.SurveyResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检验申请受理与登轮检验。 */
@Service
public class SurveyService {

    /** 签发终态后迟到登轮记录的事件类型。 */
    public static final String EVENT_LATE_BOARD_RECORD = "LATE_BOARD_RECORD";

    private final SurveyRepository surveyRepository;
    private final ShipRepository shipRepository;
    private final DefectRepository defectRepository;
    private final SurveyEventRepository eventRepository;
    private final SurveyItemCompletionRepository completionRepository;
    private final IdempotentExecutor executor;

    public SurveyService(SurveyRepository surveyRepository,
                         ShipRepository shipRepository,
                         DefectRepository defectRepository,
                         SurveyEventRepository eventRepository,
                         SurveyItemCompletionRepository completionRepository,
                         IdempotentExecutor executor) {
        this.surveyRepository = surveyRepository;
        this.shipRepository = shipRepository;
        this.defectRepository = defectRepository;
        this.eventRepository = eventRepository;
        this.completionRepository = completionRepository;
        this.executor = executor;
    }

    /**
     * 申请检验（计划→申请）。受理条件（UTC 日界）：
     * 今天落在该类型当前窗口闭区间内（恰在窗口首日/末日均可）；
     * 同船同类型无其他进行中检验（唯一索引兜底，并发唯一受理）。
     */
    public ApiResult<SurveyResponse> apply(ApplySurveyRequest request) {
        Ship ship = shipRepository.findByImo(request.shipImo())
                .orElseThrow(() -> new NotFoundException("SHIP_NOT_FOUND",
                        "船舶不存在: " + request.shipImo()));
        SurveyType type = request.type();

        String fp = Fingerprint.of(request.requestId(), "APPLY_SURVEY",
                Map.of("shipImo", request.shipImo(), "type", type.name()));
        return executor.run(request.requestId(), fp, SurveyResponse.class, () -> {
            LocalDate nowUtc = LocalDate.ofInstant(executor.now(), java.time.ZoneOffset.UTC);
            SurveyWindow window = SurveyWindowCalculator
                    .windowContaining(ship, type, nowUtc)
                    .orElseThrow(() -> new BusinessRuleException("OUTSIDE_WINDOW",
                            "当前日期不在" + typeName(type) + "检验窗口内（UTC，首尾含当日）"));
            surveyRepository.findInProgress(ship.id(), type).ifPresent(s -> {
                throw new ConflictException("SURVEY_IN_PROGRESS",
                        "同一船舶同一检验类型已有进行中检验: surveyId=" + s.id());
            });
            try {
                Survey survey = surveyRepository.insert(
                        request.requestId(), ship.id(), type,
                        window.anchor(), window.start(), window.end(),
                        fp, executor.now());
                return SurveyResponse.from(survey, ship.imo());
            } catch (SurveyRepository.ActiveSurveyConflict e) {
                throw new ConflictException("SURVEY_IN_PROGRESS",
                        "并发申请：同一船舶同一检验类型仅受理一个进行中检验");
            }
        });
    }

    /**
     * 登轮检验（申请→登轮）。完成日须落在窗口（含已批准展期末日）闭区间内。
     * 检验已发证（终态）后迟到的登轮记录只登记 {@link #EVENT_LATE_BOARD_RECORD} 事件。
     */
    public ApiResult<BoardResponse> board(BoardSurveyRequest request) {
        Survey survey = surveyRepository.findById(request.surveyId())
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + request.surveyId()));
        Ship ship = shipRepository.findById(survey.shipId()).orElseThrow();
        // 完成项目集合规范化（参数非法 400，不占幂等键）；null 表示全部必需项目。
        List<String> completedItems = normalizeCompletedItems(
                request.completedItems(), survey.type());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("surveyId", request.surveyId());
        fields.put("completedDate", request.completedDate().toString());
        fields.put("defects", request.defects() == null ? List.of()
                : request.defects().stream()
                .map(d -> Map.of("severity", d.severity().name(),
                        "description", d.description(),
                        "foundDate", d.foundDate().toString()))
                .toList());
        fields.put("completedItems", completedItems == null ? "ALL" : completedItems);
        String fp = Fingerprint.of(request.requestId(), "BOARD_SURVEY", fields);

        return executor.run(request.requestId(), fp, BoardResponse.class, () -> {
            if (survey.status() == SurveyStatus.CERTIFIED) {
                // 终态后迟到：只记事件，不改变状态、不产生新证书。
                eventRepository.insert(survey.id(), EVENT_LATE_BOARD_RECORD,
                        "{\"completedDate\":\"" + request.completedDate()
                                + "\",\"defectCount\":"
                                + (request.defects() == null ? 0 : request.defects().size()) + "}",
                        executor.now());
                List<DefectResponse> existing = defectRepository.findBySurvey(survey.id())
                        .stream().map(DefectResponse::from).toList();
                return new BoardResponse(SurveyResponse.from(survey, ship.imo()),
                        existing, true);
            }
            if (survey.status() != SurveyStatus.APPLIED) {
                throw new ConflictException("SURVEY_ALREADY_BOARDED",
                        "检验已完成登轮: surveyId=" + survey.id());
            }
            LocalDate effectiveEnd = survey.extensionUntil() != null
                    ? survey.extensionUntil() : survey.windowEnd();
            if (request.completedDate().isBefore(survey.windowStart())
                    || request.completedDate().isAfter(effectiveEnd)) {
                throw new BusinessRuleException("OUTSIDE_WINDOW",
                        "登轮完成日 " + request.completedDate()
                                + " 不在检验窗口 [" + survey.windowStart() + ", "
                                + effectiveEnd + "] 内（UTC，首尾含当日）");
            }
            List<DefectInput> inputs = request.defects() == null ? List.of() : request.defects();
            for (DefectInput d : inputs) {
                if (d.foundDate().isAfter(request.completedDate())) {
                    throw new BusinessRuleException("DEFECT_DATE_INVALID",
                            "缺陷发现日不得晚于登轮完成日: " + d.foundDate());
                }
            }
            surveyRepository.markBoarded(survey.id(), executor.now());
            // 记录登轮项目完成证明：null 表示该类型全部必需项目均已完成（兼容旧调用）。
            List<SurveyItem> completed = completedItems == null
                    ? List.copyOf(SurveyItem.requiredFor(survey.type()))
                    : completedItems.stream().map(SurveyItem::valueOf).toList();
            for (SurveyItem item : completed) {
                completionRepository.insert(survey.id(), item, executor.now());
            }
            List<DefectResponse> defects = new java.util.ArrayList<>();
            for (DefectInput d : inputs) {
                LocalDate deadline = switch (d.severity()) {
                    // 一般缺陷限 30 日复查（含第 30 日）；重大缺陷发证前必须完成，无固定期限。
                    case MINOR -> SurveyWindowCalculator.minorDeadline(d.foundDate());
                    case MAJOR -> null;
                };
                Defect saved = defectRepository.insert(survey.id(), d.severity(),
                        d.description(), d.foundDate(), deadline,
                        request.requestId(), fp, executor.now());
                defects.add(DefectResponse.from(saved));
            }
            Survey updated = surveyRepository.findById(survey.id()).orElseThrow();
            return new BoardResponse(SurveyResponse.from(updated, ship.imo()), defects, false);
        });
    }

    /** 查询检验详情。 */
    public Survey getSurveyOrThrow(long surveyId) {
        return surveyRepository.findById(surveyId)
                .orElseThrow(() -> new NotFoundException("SURVEY_NOT_FOUND",
                        "检验不存在: surveyId=" + surveyId));
    }

    /** 查询检验视图（携带船舶 IMO）。 */
    public SurveyResponse getResponse(long surveyId) {
        Survey survey = getSurveyOrThrow(surveyId);
        Ship ship = shipRepository.findById(survey.shipId()).orElseThrow();
        return SurveyResponse.from(survey, ship.imo());
    }

    private static String typeName(SurveyType type) {
        return switch (type) {
            case ANNUAL -> "年度";
            case INTERMEDIATE -> "中间";
            case SPECIAL -> "特别";
        };
    }

    /**
     * 完成项目集合规范化：null 原样返回（表示全部必需项目均完成，兼容旧调用）；
     * 显式列表（可为空，表示本次登轮无项目完成、全部依赖互认覆盖）
     * 须无重复且为必需项目子集，否则参数非法 400。
     */
    private static List<String> normalizeCompletedItems(List<SurveyItem> items,
                                                        SurveyType type) {
        if (items == null) {
            return null;
        }
        List<String> sorted = items.stream().map(SurveyItem::name).distinct().sorted().toList();
        if (sorted.size() != items.size()) {
            throw new BadRequestException("DUPLICATE_ITEM", "完成项目集合存在重复项目代码");
        }
        java.util.Set<SurveyItem> required = SurveyItem.requiredFor(type);
        for (SurveyItem item : items) {
            if (!required.contains(item)) {
                throw new BadRequestException("ITEM_NOT_REQUIRED",
                        "项目 " + item + " 不是" + typeName(type) + "检验的必需项目");
            }
        }
        return sorted;
    }
}
