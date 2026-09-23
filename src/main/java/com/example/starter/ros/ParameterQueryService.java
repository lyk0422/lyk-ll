package com.example.starter.ros;

import com.example.starter.ros.db.NodeStateRepository;
import com.example.starter.ros.db.SchemaRepository;
import com.example.starter.ros.support.ParamValues;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数读取与候选快照构建：默认值与已提交值合并。
 */
@Service
public class ParameterQueryService {

    private final SchemaRepository schemaRepository;
    private final NodeStateRepository nodeStateRepository;

    public ParameterQueryService(SchemaRepository schemaRepository,
                                 NodeStateRepository nodeStateRepository) {
        this.schemaRepository = schemaRepository;
        this.nodeStateRepository = nodeStateRepository;
    }

    /**
     * 读取节点生效参数：先铺 Schema 默认值，再用已提交值（含显式置空）覆盖。
     * 待重启配置不参与合并，绝不伪装成已应用。
     */
    public Map<String, Object> effectiveValues(String nodeId) {
        List<ParameterSchema> schemas = schemaRepository.findByNode(nodeId);
        Map<String, String> committed = nodeStateRepository.committedValues(nodeId);
        Map<String, Object> result = new LinkedHashMap<>();
        for (ParameterSchema schema : schemas) {
            if (committed.containsKey(schema.key())) {
                String text = committed.get(schema.key());
                if (text != null) {
                    result.put(schema.key(), ParamValues.parse(schema.type(), text));
                }
            } else if (schema.defaultValue() != null) {
                result.put(schema.key(), ParamValues.parse(schema.type(), schema.defaultValue()));
            }
        }
        return result;
    }

    /** 读取节点全部 Schema。 */
    public List<ParameterSchema> schemas(String nodeId) {
        return schemaRepository.findByNode(nodeId);
    }

    /**
     * 基于“当前生效值 + 本次变更”构建整笔更新后的候选完整快照（强类型）。
     *
     * @return 候选快照（保持 Schema 顺序）
     * @throws SchemaValidationException 出现未知参数或类型不匹配
     */
    public Map<String, Object> buildCandidate(String nodeId, Map<String, Object> changes) {
        List<ParameterSchema> schemas = schemaRepository.findByNode(nodeId);
        Map<String, ParameterSchema> byKey = new LinkedHashMap<>();
        for (ParameterSchema schema : schemas) {
            byKey.put(schema.key(), schema);
        }
        List<String> errors = new java.util.ArrayList<>();
        for (String key : changes.keySet()) {
            if (!byKey.containsKey(key)) {
                errors.add("node '" + nodeId + "' has no parameter named '" + key + "'");
            }
        }
        if (!errors.isEmpty()) {
            throw new SchemaValidationException(errors);
        }

        Map<String, String> committed = nodeStateRepository.committedValues(nodeId);
        Map<String, Object> candidate = new LinkedHashMap<>();
        for (ParameterSchema schema : schemas) {
            if (changes.containsKey(schema.key())) {
                Object raw = changes.get(schema.key());
                if (raw == null) {
                    // null 表示重置：回退到默认值（无默认值则该键缺席，交由必填校验）
                    if (schema.defaultValue() != null) {
                        candidate.put(schema.key(), ParamValues.parse(schema.type(), schema.defaultValue()));
                    }
                } else {
                    try {
                        candidate.put(schema.key(), ParamValues.coerce(schema.type(), raw));
                    } catch (IllegalArgumentException ex) {
                        errors.add("parameter '" + schema.key() + "': " + ex.getMessage());
                    }
                }
            } else if (committed.containsKey(schema.key())) {
                String text = committed.get(schema.key());
                if (text != null) {
                    candidate.put(schema.key(), ParamValues.parse(schema.type(), text));
                }
            } else if (schema.defaultValue() != null) {
                candidate.put(schema.key(), ParamValues.parse(schema.type(), schema.defaultValue()));
            }
        }
        if (!errors.isEmpty()) {
            throw new SchemaValidationException(errors);
        }
        return candidate;
    }
}
