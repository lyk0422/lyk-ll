package com.example.starter.survey.service;

import com.example.starter.survey.data.RecognitionRuleRepository;
import com.example.starter.survey.domain.RecognitionRule;
import com.example.starter.survey.domain.SurveyItem;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.CreateRuleRequest;
import com.example.starter.survey.web.dto.RuleResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合成互认规则版本服务。
 * 规则创建后不可变；项目集合去重后按名称排序写入，保证指纹与存储无歧义。
 */
@Service
public class RecognitionRuleService {

    private final RecognitionRuleRepository ruleRepository;
    private final IdempotentExecutor executor;

    public RecognitionRuleService(RecognitionRuleRepository ruleRepository,
                                  IdempotentExecutor executor) {
        this.ruleRepository = ruleRepository;
        this.executor = executor;
    }

    /** 创建规则版本（幂等；同版本号异内容 409）。 */
    public ApiResult<RuleResponse> create(CreateRuleRequest request) {
        List<String> items = normalizeItems(request.items());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ruleVersion", request.ruleVersion());
        fields.put("flagCode", request.flagCode());
        fields.put("maxEvidenceDays", request.maxEvidenceDays());
        fields.put("items", items);
        String fp = Fingerprint.of(request.requestId(), "CREATE_RECOGNITION_RULE", fields);

        return executor.run(request.requestId(), fp, RuleResponse.class, () -> {
            ruleRepository.findByVersion(request.ruleVersion()).ifPresent(existing -> {
                throw new ConflictException("RULE_VERSION_EXISTS",
                        "规则版本已存在: " + request.ruleVersion());
            });
            try {
                RecognitionRule rule = ruleRepository.insert(
                        request.requestId(), request.ruleVersion(), request.flagCode(),
                        request.maxEvidenceDays(),
                        items.stream().map(SurveyItem::valueOf).toList(),
                        fp, executor.now());
                return RuleResponse.from(rule, ruleRepository.findItems(rule.id()));
            } catch (RecognitionRuleRepository.RuleVersionConflict e) {
                throw new ConflictException("RULE_VERSION_EXISTS",
                        "并发创建：规则版本已存在: " + request.ruleVersion());
            }
        });
    }

    /** 查询规则版本（含项目映射）。 */
    public RuleResponse getByVersion(int ruleVersion) {
        RecognitionRule rule = ruleRepository.findByVersion(ruleVersion)
                .orElseThrow(() -> new NotFoundException("RULE_NOT_FOUND",
                        "互认规则版本不存在: " + ruleVersion));
        return RuleResponse.from(rule, ruleRepository.findItems(rule.id()));
    }

    /** 列出全部规则版本。 */
    public List<RuleResponse> list() {
        return ruleRepository.findAll().stream()
                .map(rule -> RuleResponse.from(rule, ruleRepository.findItems(rule.id())))
                .toList();
    }

    /** 项目集合去重并按名称排序；重复项目视为参数非法。 */
    static List<String> normalizeItems(List<SurveyItem> items) {
        List<String> sorted = items.stream().map(SurveyItem::name).distinct().sorted().toList();
        if (sorted.size() != items.size()) {
            throw new com.example.starter.survey.web.BadRequestException(
                    "DUPLICATE_ITEM", "项目集合存在重复项目代码");
        }
        return sorted;
    }
}
