package com.example.starter.ct;

import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.ct.repo.AlertRepository;
import com.example.starter.ct.repo.ObserverRepository;

/**
 * 观察者上报与分裂视图告警服务。
 *
 * <p>上报先验签；同一 treeSize 出现不同有效根时原子创建唯一 SPLIT_VIEW 告警并保留双方证据；
 * 重复上报幂等；较小树头不倒退观察者最新位置。整个处理在事务中串行化，
 * 与追加并发时只读取已提交前缀。</p>
 */
@Service
public class ObserverService {

    private static final HexFormat HEX = HexFormat.of();

    private final ObserverRepository observerRepository;
    private final AlertRepository alertRepository;
    private final CtLogService ctLogService;
    private final SignatureService signatureService;
    private final Clock clock;

    public ObserverService(ObserverRepository observerRepository, AlertRepository alertRepository,
            CtLogService ctLogService, SignatureService signatureService, Clock clock) {
        this.observerRepository = observerRepository;
        this.alertRepository = alertRepository;
        this.ctLogService = ctLogService;
        this.signatureService = signatureService;
        this.clock = clock;
    }

    /**
     * 上报处理结果。alertCreated 为 true 时 alertId 非空。
     */
    public record ReportResult(boolean accepted, boolean alertCreated, Long alertId, long observerPosition) {
    }

    /**
     * 处理观察者树头上报；签名非法抛 401，参数非法抛 400。
     */
    @Transactional
    public synchronized ReportResult report(String observerId, long treeSize, String rootHash, long timestamp,
            String signature) {
        if (treeSize < 1) {
            throw CtException.badRequest("INVALID_TREE_SIZE", "树大小必须大于 0: " + treeSize);
        }
        String normalizedRoot = normalizeRootHash(rootHash);
        if (!signatureService.verify(observerId, treeSize, normalizedRoot, timestamp, signature)) {
            throw CtException.unauthorized("SIGNATURE_INVALID", "观察者上报签名验证失败");
        }

        long now = clock.millis();
        // 记录上报证据；重复上报幂等，不重复告警。
        observerRepository.insertReport(observerId, treeSize, normalizedRoot, timestamp, now);

        boolean alertCreated = false;
        Long alertId = null;
        long localSize = ctLogService.currentTreeSize();
        if (treeSize <= localSize) {
            String localRoot = ctLogService.treeHead(treeSize).rootHash();
            if (!localRoot.equals(normalizedRoot)) {
                Optional<Long> id = alertRepository.insertIfAbsent(observerId, treeSize, localRoot, normalizedRoot,
                        timestamp, now);
                alertCreated = id.isPresent();
                alertId = id.orElse(null);
            }
        }
        // treeSize 超出本地已提交前缀时无法比较，仅推进观察者位置。

        observerRepository.upsertState(observerId, treeSize, normalizedRoot, now);
        long position = observerRepository.findState(observerId)
                .map(ObserverRepository.ObserverStateRow::maxTreeSize)
                .orElse(treeSize);
        return new ReportResult(true, alertCreated, alertId, position);
    }

    /**
     * 查询全部分裂视图告警。
     */
    @Transactional(readOnly = true)
    public List<AlertRepository.AlertRow> alerts() {
        return alertRepository.findAll();
    }

    private static String normalizeRootHash(String rootHash) {
        if (rootHash == null) {
            throw CtException.badRequest("INVALID_ROOT_HASH", "根哈希缺失");
        }
        String normalized = rootHash.toLowerCase(Locale.ROOT);
        if (normalized.length() != 64) {
            throw CtException.badRequest("INVALID_ROOT_HASH", "根哈希必须为 64 位十六进制: " + rootHash);
        }
        try {
            HEX.parseHex(normalized);
        } catch (IllegalArgumentException e) {
            throw CtException.badRequest("INVALID_ROOT_HASH", "根哈希必须为十六进制: " + rootHash);
        }
        return normalized;
    }
}
