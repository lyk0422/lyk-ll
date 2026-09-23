package com.example.starter.rosconfig.service;

import com.example.starter.rosconfig.dto.ConfigView;
import com.example.starter.rosconfig.exception.BadRequestException;
import com.example.starter.rosconfig.exception.NotFoundException;
import com.example.starter.rosconfig.model.ParameterSchema;
import com.example.starter.rosconfig.repo.ConfigRepository;
import com.example.starter.rosconfig.repo.SchemaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置读取与待重启静态参数管理。
 */
@Service
public class ConfigQueryService {

    private final SchemaRepository schemaRepository;
    private final ConfigRepository configRepository;
    private final SnapshotMerger snapshotMerger;
    private final JsonValues jsonValues;
    private final SchemaValidator schemaValidator;

    public ConfigQueryService(SchemaRepository schemaRepository,
                              ConfigRepository configRepository,
                              SnapshotMerger snapshotMerger,
                              JsonValues jsonValues,
                              SchemaValidator schemaValidator) {
        this.schemaRepository = schemaRepository;
        this.configRepository = configRepository;
        this.snapshotMerger = snapshotMerger;
        this.jsonValues = jsonValues;
        this.schemaValidator = schemaValidator;
    }

    /**
     * 读取节点合并视图：Schema 默认值与已提交值合并；返回动态属性标记与待重启静态参数名。
     */
    public ConfigView view(String node) {
        List<ParameterSchema> schemas = schemaRepository.findByNode(node);
        if (schemas.isEmpty()) {
            throw new NotFoundException("node not found: " + node);
        }
        long version = configRepository.getOrInitVersion(node);
        Map<String, Object> effective = snapshotMerger.effective(schemas, node);

        Map<String, Boolean> dynamic = new LinkedHashMap<>();
        String group = null;
        for (ParameterSchema schema : schemas) {
            dynamic.put(schema.name(), schema.dynamic());
            if (group == null) {
                group = schema.group();
            }
        }

        Map<String, String> pendingTexts = configRepository.findPendingValues(node);
        List<String> pendingRestart = new ArrayList<>(pendingTexts.keySet());

        return new ConfigView(node, version, group, effective, dynamic, pendingRestart);
    }

    /**
     * 创建待重启静态参数配置：
     * 仅 Schema 中标记为非动态的参数允许进入 PENDING；静态参数热更新入口被拒绝，只能走这里。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConfigView stagePending(String node, String name, Object value) {
        List<ParameterSchema> schemas = schemaRepository.findByNode(node);
        ParameterSchema schema = schemas.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("parameter not found: " + node + "/" + name));
        if (schema.dynamic()) {
            throw new BadRequestException("parameter " + name
                    + " is dynamic; use hot-update transaction instead");
        }
        Object coerced = value == null ? null : schema.type().coerce(value);
        Map<String, Object> candidate = snapshotMerger.effective(schemas, node);
        candidate.put(name, coerced);
        String violation = schemaValidator.validate(schemas, candidate);
        if (violation != null) {
            throw new BadRequestException(violation);
        }
        configRepository.getOrInitVersion(node);
        if (coerced == null) {
            configRepository.deleteValue(node, name, ConfigRepository.SOURCE_PENDING);
        } else {
            configRepository.upsertValue(node, name, ConfigRepository.SOURCE_PENDING,
                    jsonValues.write(coerced));
        }
        return view(node);
    }
}
