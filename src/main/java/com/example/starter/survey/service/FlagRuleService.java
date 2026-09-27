package com.example.starter.survey.service;

import com.example.starter.survey.data.FlagRuleRepository;
import com.example.starter.survey.domain.FlagRuleVersion;
import com.example.starter.survey.support.Fingerprint;
import com.example.starter.survey.support.IdempotentExecutor;
import com.example.starter.survey.web.BadRequestException;
import com.example.starter.survey.web.ConflictException;
import com.example.starter.survey.web.NotFoundException;
import com.example.starter.survey.web.dto.ApiResult;
import com.example.starter.survey.web.dto.FlagRuleMappingInput;
import com.example.starter.survey.web.dto.FlagRuleVersionResponse;
import com.example.starter.survey.web.dto.UpsertFlagRuleRequest;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 船旗互认规则版本管理。
 * 规则版本不可变：变更携带 expectedVersion，与当前版本一致才受理，
 * 成功变更插入“当前版本+1”的新版本行；历史版本保留供互认绑定冻结引用。
 */
@Service
public class FlagRuleService {

    private final FlagRuleRepository ruleRepository;
    private final IdempotentExecutor executor;

    public FlagRuleService(FlagRuleRepository ruleRepository, IdempotentExecutor executor) {
        this.ruleRepository = ruleRepository;
        this.executor = executor;
    }

    /** 新建/变更规则版本（幂等；expectedVersion 乐观并发，冲突 409）。 */
    public ApiResult<FlagRuleVersionResponse> upsert(UpsertFlagRuleRequest request) {
        List<FlagRuleMappingInput> mappings = sortedDistinctMappings(request);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("flagCode", request.flagCode());
        fields.put("expectedVersion", request.expectedVersion());
        fields.put("maxEvidenceAgeDays", request.maxEvidenceAgeDays());
        fields.put("mappings", mappings.stream()
                .map(m -> Map.of("flagItemCode", m.flagItemCode(),
                        "classItemCode", m.classItemCode()))
                .toList());
        String fp = Fingerprint.of(request.requestId(), "UPSERT_FLAG_RULE", fields);

        return executor.run(request.requestId(), fp, FlagRuleVersionResponse.class, () -> {
            int current = ruleRepository.findLatest(request.flagCode())
                    .map(FlagRuleVersion::version).orElse(0);
            if (current != request.expectedVersion()) {
                throw new ConflictException("RULE_VERSION_CONFLICT",
                        "规则版本冲突：当前版本 " + current + "，请求 expectedVersion="
                                + request.expectedVersion());
            }
            FlagRuleVersion created = ruleRepository.insertVersion(request.requestId(),
                    request.flagCode(), current + 1, request.maxEvidenceAgeDays(), executor.now());
            for (FlagRuleMappingInput m : mappings) {
                ruleRepository.insertItem(created.id(), m.flagItemCode(), m.classItemCode());
            }
            return FlagRuleVersionResponse.from(created, ruleRepository.findItems(created.id()));
        });
    }

    /** 查询某船旗当前最新规则版本。 */
    public FlagRuleVersionResponse getCurrent(String flagCode) {
        FlagRuleVersion latest = ruleRepository.findLatest(flagCode)
                .orElseThrow(() -> new NotFoundException("RULE_NOT_FOUND",
                        "船旗互认规则不存在: flagCode=" + flagCode));
        return FlagRuleVersionResponse.from(latest, ruleRepository.findItems(latest.id()));
    }

    /** 列出某船旗全部规则版本（版本链查询）。 */
    public List<FlagRuleVersionResponse> listVersions(String flagCode) {
        List<FlagRuleVersion> versions = ruleRepository.findVersions(flagCode);
        if (versions.isEmpty()) {
            throw new NotFoundException("RULE_NOT_FOUND",
                    "船旗互认规则不存在: flagCode=" + flagCode);
        }
        return versions.stream()
                .map(v -> FlagRuleVersionResponse.from(v, ruleRepository.findItems(v.id())))
                .toList();
    }

    /** 映射按船旗项目排序并校验无重复项目代码（船旗/船级两侧均须唯一）。 */
    private List<FlagRuleMappingInput> sortedDistinctMappings(UpsertFlagRuleRequest request) {
        Set<String> flagCodes = new HashSet<>();
        Set<String> classCodes = new HashSet<>();
        for (FlagRuleMappingInput m : request.mappings()) {
            if (!flagCodes.add(m.flagItemCode())) {
                throw new BadRequestException("DUPLICATE_FLAG_ITEM",
                        "船旗检查项目重复: " + m.flagItemCode());
            }
            if (!classCodes.add(m.classItemCode())) {
                throw new BadRequestException("DUPLICATE_CLASS_ITEM",
                        "船级检查项目重复: " + m.classItemCode());
            }
        }
        return request.mappings().stream()
                .sorted(Comparator.comparing(FlagRuleMappingInput::flagItemCode))
                .toList();
    }
}
