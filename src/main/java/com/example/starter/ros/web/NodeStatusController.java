package com.example.starter.ros.web;

import com.example.starter.ros.repo.ParameterJdbcRepository;
import com.example.starter.ros.service.SchemaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 节点状态查询：当前单调版本与合并后的参数视图。 */
@RestController
@RequestMapping("/api/ros/nodes")
public class NodeStatusController {

    private final ParameterJdbcRepository parameterRepository;
    private final SchemaService schemaService;

    public NodeStatusController(ParameterJdbcRepository parameterRepository, SchemaService schemaService) {
        this.parameterRepository = parameterRepository;
        this.schemaService = schemaService;
    }

    @GetMapping("/{node}/status")
    public Map<String, Object> status(@PathVariable String node) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("node", node);
        status.put("version", parameterRepository.currentVersion(node));
        status.put("parameters", schemaService.mergedView(node));
        return status;
    }
}
