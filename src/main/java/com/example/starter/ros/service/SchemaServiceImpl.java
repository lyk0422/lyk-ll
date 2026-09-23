package com.example.starter.ros.service;

import com.example.starter.ros.model.ParamSchema;
import com.example.starter.ros.repo.ParameterJdbcRepository;
import com.example.starter.ros.repo.SchemaJdbcRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Schema 查询、默认值合并与候选快照构建。 */
@Service
public class SchemaServiceImpl implements SchemaService {

    private final SchemaJdbcRepository schemaRepository;
    private final ParameterJdbcRepository parameterRepository;

    public SchemaServiceImpl(SchemaJdbcRepository schemaRepository,
                             ParameterJdbcRepository parameterRepository) {
        this.schemaRepository = schemaRepository;
        this.parameterRepository = parameterRepository;
    }

    @Override
    public List<ParamSchema> all() {
        return schemaRepository.findAll();
    }

    @Override
    public List<ParamSchema> byNode(String node) {
        return schemaRepository.findByNode(node);
    }

    @Override
    public ParamSchema find(String node, String group, String name) {
        return schemaRepository.findByNode(node).stream()
                .filter(s -> s.group().equals(group) && s.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public Map<String, Object> mergedView(String node) {
        Map<String, Object> view = new LinkedHashMap<>();
        for (ParamSchema schema : schemaRepository.findByNode(node)) {
            String key = schema.qualifiedName();
            if (schema.defaultValue() != null) {
                view.put(key, schema.type().fromStored(schema.defaultValue()));
            }
        }
        parameterRepository.findValues(node).forEach((key, stored) -> {
            ParamSchema schema = locate(node, key);
            if (schema == null) {
                // 无 Schema 的历史值按字符串原样返回
                view.put(key, stored);
            } else if (stored == null) {
                view.put(key, null);
            } else {
                view.put(key, schema.type().fromStored(stored));
            }
        });
        return view;
    }

    @Override
    public Map<String, Object> buildCandidate(String node, Map<String, Map<String, Object>> changes) {
        // 从“默认值 + 已提交值”的整笔更新前快照出发
        Map<String, Object> candidate = new LinkedHashMap<>(mergedView(node));
        if (changes == null) {
            return candidate;
        }
        changes.forEach((group, params) -> {
            if (params == null) {
                return;
            }
            params.forEach((name, raw) -> {
                ParamSchema schema = find(node, group, name);
                if (schema == null) {
                    throw new IllegalArgumentException(
                            "节点 " + node + " 不存在参数 Schema: " + group + "." + name);
                }
                Object typed = schema.type().parse(raw);
                candidate.put(schema.qualifiedName(), typed);
            });
        });
        return candidate;
    }

    /** 解析存储值时按限定键定位 Schema。 */
    private ParamSchema locate(String node, String qualifiedKey) {
        int dot = qualifiedKey.indexOf('.');
        if (dot < 0) {
            return null;
        }
        return find(node, qualifiedKey.substring(0, dot), qualifiedKey.substring(dot + 1));
    }
}
