package com.example.starter.ct;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.ct.repo.CtLeafRepository;

/**
 * 证书透明日志核心服务：追加叶、树头与两类证明。
 *
 * <p>追加在单实例内串行化并在事务中提交，保证并发下叶索引唯一连续；
 * 树头与证明读取只对应已提交前缀。日志追加的叶内容为 DER 字节的 SHA-256 摘要，
 * Merkle 叶哈希为 SHA-256(0x00 || 摘要字节)。</p>
 */
@Service
public class CtLogService {

    private static final HexFormat HEX = HexFormat.of();

    private final CtLeafRepository leafRepository;
    private final Clock clock;

    public CtLogService(CtLeafRepository leafRepository, Clock clock) {
        this.leafRepository = leafRepository;
        this.clock = clock;
    }

    /**
     * 追加结果。idempotent 为 true 表示命中同 requestId 的首次提交。
     */
    public record AppendResult(long leafIndex, long treeSize, String entryHash, String leafHash, boolean idempotent) {
    }

    /**
     * 指定树大小的树头。
     */
    public record TreeHead(long treeSize, String rootHash) {
    }

    /**
     * 包含证明。
     */
    public record InclusionProof(long leafIndex, long treeSize, String leafHash, List<String> auditPath) {
    }

    /**
     * 一致性证明。
     */
    public record ConsistencyProof(long first, long second, List<String> consistencyPath) {
    }

    /**
     * 追加 DER 字节；同 requestId 同条目返回首次索引，同键异条目抛 409。
     */
    @Transactional
    public synchronized AppendResult append(String requestId, byte[] derBytes) {
        String entryHash = HEX.formatHex(sha256(derBytes));
        String leafHash = HEX.formatHex(MerkleTree.leafHash(HEX.parseHex(entryHash)));

        var existing = leafRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            var row = existing.get();
            if (!row.entryHash().equals(entryHash)) {
                throw CtException.conflict("REQUEST_ID_CONFLICT",
                        "requestId 已对应不同条目: " + requestId);
            }
            return new AppendResult(row.leafIndex(), leafRepository.count(), row.entryHash(), row.leafHash(), true);
        }

        long leafIndex = leafRepository.count();
        leafRepository.insert(leafIndex, requestId, entryHash, leafHash, clock.millis());
        return new AppendResult(leafIndex, leafIndex + 1, entryHash, leafHash, false);
    }

    /**
     * 读取树头；treeSize 为 null 表示当前最新。零大小或越界抛稳定错误。
     */
    @Transactional(readOnly = true)
    public TreeHead treeHead(Long treeSize) {
        long current = leafRepository.count();
        long size = resolveTreeSize(treeSize, current);
        List<byte[]> leaves = loadLeafHashes(size);
        return new TreeHead(size, HEX.formatHex(MerkleTree.rootHash(leaves, 0, leaves.size())));
    }

    /**
     * 生成包含证明；索引越界或树大小非法抛稳定错误。
     */
    @Transactional(readOnly = true)
    public InclusionProof inclusionProof(long leafIndex, Long treeSize) {
        long current = leafRepository.count();
        long size = resolveTreeSize(treeSize, current);
        if (leafIndex < 0 || leafIndex >= size) {
            throw CtException.badRequest("LEAF_INDEX_OUT_OF_RANGE",
                    "叶索引越界: leafIndex=" + leafIndex + ", treeSize=" + size);
        }
        List<byte[]> leaves = loadLeafHashes(size);
        List<String> path = MerkleTree.auditPath(leaves, (int) leafIndex).stream().map(HEX::formatHex).toList();
        return new InclusionProof(leafIndex, size, HEX.formatHex(leaves.get((int) leafIndex)), path);
    }

    /**
     * 生成一致性证明；非法新旧树大小抛稳定错误。
     */
    @Transactional(readOnly = true)
    public ConsistencyProof consistencyProof(long first, long second) {
        long current = leafRepository.count();
        if (first < 1 || second < 1 || first > second || second > current) {
            throw CtException.badRequest("INVALID_TREE_SIZE_RANGE",
                    "非法新旧树大小: first=" + first + ", second=" + second + ", current=" + current);
        }
        List<byte[]> leaves = loadLeafHashes(second);
        List<String> path = MerkleTree.consistencyPath(leaves, (int) first, (int) second).stream()
                .map(HEX::formatHex).toList();
        return new ConsistencyProof(first, second, path);
    }

    /**
     * 当前已提交的树大小。
     */
    @Transactional(readOnly = true)
    public long currentTreeSize() {
        return leafRepository.count();
    }

    private long resolveTreeSize(Long treeSize, long current) {
        long size = treeSize == null ? current : treeSize;
        if (size <= 0) {
            throw CtException.badRequest("INVALID_TREE_SIZE", "树大小必须大于 0: " + size);
        }
        if (size > current) {
            throw CtException.badRequest("INVALID_TREE_SIZE",
                    "树大小超出已提交前缀: treeSize=" + size + ", current=" + current);
        }
        return size;
    }

    private List<byte[]> loadLeafHashes(long treeSize) {
        return leafRepository.findLeafHashesUpTo(treeSize).stream().map(HEX::parseHex).toList();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
