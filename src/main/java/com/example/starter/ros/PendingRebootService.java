package com.example.starter.ros;

import com.example.starter.ros.db.PendingRebootRepository;
import com.example.starter.ros.db.SchemaRepository;
import com.example.starter.ros.support.ParamValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 静态参数的待重启配置服务。
 *
 * <p>该路径只登记“重启后生效”的新值：不改变已提交值、不推进版本、不产生热更新事件，
 * 响应中 {@code applied} 恒为 false，绝不伪装成已应用。
 */
@Service
public class PendingRebootService {

    private final PendingRebootRepository pendingRebootRepository;
    private final SchemaRepository schemaRepository;
    private final Clock clock;

    public PendingRebootService(PendingRebootRepository pendingRebootRepository,
                                SchemaRepository schemaRepository,
                                Clock rosClock) {
        this.pendingRebootRepository = pendingRebootRepository;
        this.schemaRepository = schemaRepository;
        this.clock = rosClock;
    }

    @Transactional
    public PendingRebootRequest.Response register(String nodeId, PendingRebootRequest request) {
        Map<String, Object> values = request.values() == null ? Map.of() : request.values();
        List<ParameterSchema> schemas = schemaRepository.findByNode(nodeId);
        Map<String, ParameterSchema> byKey = new LinkedHashMap<>();
        for (ParameterSchema schema : schemas) {
            byKey.put(schema.key(), schema);
        }

        List<PendingRebootRecord> existing =
                pendingRebootRepository.findByTransaction(request.transactionId(), nodeId);
        if (!existing.isEmpty()) {
            return replayOrConflict(nodeId, request, values, existing);
        }

        List<String> errors = new ArrayList<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            ParameterSchema schema = byKey.get(entry.getKey());
            if (schema == null) {
                errors.add("node '" + nodeId + "' has no parameter named '" + entry.getKey() + "'");
                continue;
            }
            if (schema.dynamic()) {
                errors.add("parameter '" + entry.getKey() + "' is dynamic; change it via hot update");
                continue;
            }
            if (entry.getValue() == null) {
                errors.add("parameter '" + entry.getKey() + "' pending value must not be null");
                continue;
            }
            try {
                normalized.put(entry.getKey(), ParamValues.format(
                        ParamValues.coerce(schema.type(), entry.getValue())));
            } catch (IllegalArgumentException ex) {
                errors.add("parameter '" + entry.getKey() + "': " + ex.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new SchemaValidationException(errors);
        }

        Instant now = Instant.now(clock);
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            pendingRebootRepository.insert(new PendingRebootRecord(
                    0L, nodeId, entry.getKey(), entry.getValue(), request.transactionId(), now));
        }
        return new PendingRebootRequest.Response(
                request.transactionId(), nodeId, normalized, false, false, now);
    }

    private PendingRebootRequest.Response replayOrConflict(String nodeId,
                                                           PendingRebootRequest request,
                                                           Map<String, Object> values,
                                                           List<PendingRebootRecord> existing) {
        Map<String, String> existingValues = new LinkedHashMap<>();
        for (PendingRebootRecord record : existing) {
            existingValues.put(record.paramKey(), record.newValue());
        }
        Map<String, String> requested = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            ParameterSchema schema = schemaRepository.findByNode(nodeId).stream()
                    .filter(s -> s.key().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);
            if (schema == null || entry.getValue() == null) {
                throw new ConflictException("transactionId '" + request.transactionId()
                        + "' already exists with different parameters");
            }
            try {
                requested.put(entry.getKey(), ParamValues.format(
                        ParamValues.coerce(schema.type(), entry.getValue())));
            } catch (IllegalArgumentException ex) {
                throw new ConflictException("transactionId '" + request.transactionId()
                        + "' already exists with different parameters");
            }
        }
        if (!existingValues.equals(requested)) {
            throw new ConflictException("transactionId '" + request.transactionId()
                    + "' already exists with different parameters");
        }
        return new PendingRebootRequest.Response(
                request.transactionId(), nodeId, existingValues, false, true,
                existing.get(0).createdAt());
    }
}
