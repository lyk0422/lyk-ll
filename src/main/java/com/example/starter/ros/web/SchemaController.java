package com.example.starter.ros.web;

import com.example.starter.ros.model.ParamSchema;
import com.example.starter.ros.repo.SchemaJdbcRepository;
import com.example.starter.ros.service.SchemaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Schema 管理与参数读取（默认值合并已提交值）入口。 */
@RestController
@RequestMapping("/api/ros")
public class SchemaController {

    private final SchemaJdbcRepository schemaRepository;
    private final SchemaService schemaService;

    public SchemaController(SchemaJdbcRepository schemaRepository, SchemaService schemaService) {
        this.schemaRepository = schemaRepository;
        this.schemaService = schemaService;
    }

    @PostMapping("/schemas")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody ParamSchema schema) {
        schemaRepository.insert(schema);
    }

    @GetMapping("/schemas")
    public List<ParamSchema> all() {
        return schemaRepository.findAll();
    }

    /** 读取节点参数：默认值与已提交值合并后的视图。 */
    @GetMapping("/nodes/{node}/parameters")
    public Map<String, Object> parameters(@PathVariable String node) {
        return schemaService.mergedView(node);
    }
}
