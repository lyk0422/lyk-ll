package com.example.starter.ros.service;

import com.example.starter.ros.dto.PendingRestartRequest;
import com.example.starter.ros.error.ApiConflictException;
import com.example.starter.ros.model.ParamSchema;
import com.example.starter.ros.repo.PendingRestartJdbcRepository;
import com.example.starter.ros.time.RosClock;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 静态参数变更登记为待重启配置，不写已提交值、不推进版本、不产生热更新事件。 */
@Service
public class PendingRestartServiceImpl implements PendingRestartService {

    private final SchemaService schemaService;
    private final PendingRestartJdbcRepository pendingRestartRepository;
    private final RosClock clock;

    public PendingRestartServiceImpl(SchemaService schemaService,
                                     PendingRestartJdbcRepository pendingRestartRepository,
                                     RosClock clock) {
        this.schemaService = schemaService;
        this.pendingRestartRepository = pendingRestartRepository;
        this.clock = clock;
    }

    @Override
    public List<String> register(PendingRestartRequest request) {
        String transactionId = request.transactionId() == null || request.transactionId().isBlank()
                ? "pending-" + request.node() + "-" + clock.nowMillis()
                : request.transactionId();

        List<String> registered = new ArrayList<>();
        request.parameters().forEach((group, params) -> {
            if (params == null) {
                return;
            }
            params.forEach((name, raw) -> {
                ParamSchema schema = schemaService.find(request.node(), group, name);
                if (schema == null) {
                    throw new IllegalArgumentException(
                            "节点 " + request.node() + " 不存在参数 Schema: " + group + "." + name);
                }
                Object typed = schema.type().parse(raw);
                if (schema.dynamic()) {
                    throw new ApiConflictException(
                            "动态参数不应走待重启通道，请使用热更新: " + group + "." + name);
                }
                pendingRestartRepository.append(
                        transactionId, request.node(), group, name,
                        typed == null ? null : String.valueOf(typed), clock.nowMillis());
                registered.add(group + "." + name);
            });
        });
        return registered;
    }
}
