package com.example.starter.rosconfig.service;

import com.example.starter.rosconfig.model.ParameterSchema;
import com.example.starter.rosconfig.repo.ConfigRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选快照合并：默认值（Schema） <- 已提交值（DB） <- 本次变更集，逐层覆盖。
 * 变更值按 Schema 类型规范化；JSON null 表示显式置空（恢复默认）。
 */
@Component
public class SnapshotMerger {

    private final ConfigRepository configRepository;
    private final JsonValues jsonValues;

    public SnapshotMerger(ConfigRepository configRepository, JsonValues jsonValues) {
        this.configRepository = configRepository;
        this.jsonValues = jsonValues;
    }

    /**
     * 合并节点当前有效配置（默认值 + 已提交值），不含本次变更。
     */
    public Map<String, Object> effective(List<ParameterSchema> schemas, String node) {
        return merge(schemas, node, Map.of());
    }

    /**
     * 合并候选快照：默认值 <- 已提交值 <- 变更集。
     * 变更值按 Schema 类型规范化；变更集中出现未知参数时保留原样交由校验器报错。
     */
    public Map<String, Object> merge(List<ParameterSchema> schemas, String node, Map<String, Object> changes) {
        Map<String, ParameterSchema> byName = new LinkedHashMap<>();
        for (ParameterSchema schema : schemas) {
            byName.put(schema.name(), schema);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (ParameterSchema schema : schemas) {
            if (schema.defaultValue() != null) {
                snapshot.put(schema.name(), jsonValues.parse(schema.defaultValue()));
            }
        }

        Map<String, String> committed = configRepository.findCommittedValues(node);
        for (Map.Entry<String, String> entry : committed.entrySet()) {
            ParameterSchema schema = byName.get(entry.getKey());
            if (schema == null) {
                continue;
            }
            Object value = jsonValues.parse(entry.getValue());
            if (value == null) {
                snapshot.remove(entry.getKey());
            } else {
                snapshot.put(entry.getKey(), value);
            }
        }

        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            ParameterSchema schema = byName.get(entry.getKey());
            Object raw = entry.getValue();
            if (raw == null) {
                snapshot.remove(entry.getKey());
                continue;
            }
            Object coerced = schema == null ? raw : schema.type().coerce(raw);
            snapshot.put(entry.getKey(), coerced);
        }
        return snapshot;
    }
}
