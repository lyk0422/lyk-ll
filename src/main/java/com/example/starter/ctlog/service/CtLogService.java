package com.example.starter.ctlog.service;

import com.example.starter.ctlog.crypto.Ed25519;
import com.example.starter.ctlog.domain.LeafEntry;
import com.example.starter.ctlog.domain.ObserverHead;
import com.example.starter.ctlog.domain.SplitAlert;
import com.example.starter.ctlog.dto.AddLeafRequest;
import com.example.starter.ctlog.dto.AddLeafResponse;
import com.example.starter.ctlog.dto.ConsistencyProofResponse;
import com.example.starter.ctlog.dto.InclusionProofResponse;
import com.example.starter.ctlog.dto.ObserveRequest;
import com.example.starter.ctlog.dto.ObserveResponse;
import com.example.starter.ctlog.dto.TreeHeadResponse;
import com.example.starter.ctlog.dto.VerifyConsistencyRequest;
import com.example.starter.ctlog.dto.VerifyInclusionRequest;
import com.example.starter.ctlog.merkle.MerkleTree;
import com.example.starter.ctlog.repo.CtLogRepository;
import com.example.starter.ctlog.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * 证书透明日志核心服务。
 *
 * <p>追加在单行状态表行锁内串行化，为每条成功提交分配唯一连续叶索引；
 * 树头与证明读取只对应已提交前缀；观察者上报在观察者行锁内原子处理，
 * 分裂视图告警依赖唯一约束去重。</p>
 */
@Service
public class CtLogService {

    /**
     * 观察者签名消息的域分隔前缀。
     */
    static final String SIGN_DOMAIN = "CTLOG1";

    private final CtLogRepository repository;
    private final Ed25519 ed25519;

    public CtLogService(CtLogRepository repository, Ed25519 ed25519) {
        this.repository = repository;
        this.ed25519 = ed25519;
    }

    /**
     * 追加叶：同 requestId 同条目返回首次索引（幂等），同 requestId 异条目返回 409。
     */
    @Transactional
    public AddLeafResponse addLeaf(AddLeafRequest request) {
        byte[] derBytes = decodeBase64(request.data());
        byte[] entryHash = sha256(derBytes);
        String entryHashHex = HexFormat.of().formatHex(entryHash);

        long size = repository.lockAndGetSize();
        LeafEntry existing = repository.findLeafByRequestId(request.requestId());
        if (existing != null) {
            if (MessageDigest.isEqual(existing.entryHash(), entryHash)) {
                return new AddLeafResponse(existing.leafIndex(), entryHashHex, true);
            }
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT",
                    "requestId already used with a different entry");
        }
        repository.insertLeaf(size, request.requestId(), entryHash, entryHashHex);
        repository.incrementSize();
        return new AddLeafResponse(size, entryHashHex, false);
    }

    /**
     * 生成指定 treeSize 的树头；treeSize 为空时使用当前大小。零大小或越界返回稳定错误。
     */
    @Transactional(readOnly = true)
    public TreeHeadResponse getTreeHead(Long treeSize) {
        long size = resolveTreeSize(treeSize);
        byte[] root = MerkleTree.root(repository.leafHashesBetween(0, size));
        return new TreeHeadResponse(size, HexFormat.of().formatHex(root), System.currentTimeMillis());
    }

    /**
     * 生成 leafIndex 在指定 treeSize 下的包含证明。
     */
    @Transactional(readOnly = true)
    public InclusionProofResponse inclusionProof(long leafIndex, Long treeSize) {
        long size = resolveTreeSize(treeSize);
        if (leafIndex < 0 || leafIndex >= size) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEAF_INDEX_OUT_OF_RANGE",
                    "leafIndex must be in [0, treeSize)");
        }
        List<byte[]> leaves = repository.leafHashesBetween(0, size);
        List<byte[]> proof = MerkleTree.inclusionProof(leaves, (int) leafIndex);
        byte[] root = MerkleTree.root(leaves);
        return new InclusionProofResponse(leafIndex, size,
                HexFormat.of().formatHex(MerkleTree.leafHash(leaves.get((int) leafIndex))),
                HexFormat.of().formatHex(root), toHexList(proof));
    }

    /**
     * 生成 firstTreeSize 到 secondTreeSize（默认当前大小）的一致性证明。
     */
    @Transactional(readOnly = true)
    public ConsistencyProofResponse consistencyProof(long firstTreeSize, Long secondTreeSize) {
        long second = resolveTreeSize(secondTreeSize);
        if (firstTreeSize <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TREE_SIZE",
                    "firstTreeSize must be >= 1");
        }
        if (firstTreeSize > second) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TREE_RANGE",
                    "firstTreeSize must be <= secondTreeSize");
        }
        List<byte[]> leaves = repository.leafHashesBetween(0, second);
        List<byte[]> proof = firstTreeSize == second
                ? List.of()
                : MerkleTree.consistencyProof(leaves, (int) firstTreeSize, (int) second);
        String firstRoot = HexFormat.of().formatHex(
                MerkleTree.root(leaves.subList(0, (int) firstTreeSize)));
        String secondRoot = HexFormat.of().formatHex(MerkleTree.root(leaves));
        return new ConsistencyProofResponse(firstTreeSize, second, firstRoot, secondRoot,
                toHexList(proof));
    }

    /**
     * 本地验证包含证明。
     */
    public boolean verifyInclusion(VerifyInclusionRequest request) {
        byte[] leafHash = decodeHex(request.leafHash(), "leafHash");
        byte[] rootHash = decodeHex(request.rootHash(), "rootHash");
        List<byte[]> proof = decodeHexList(request.proof());
        return MerkleTree.verifyInclusion(leafHash, request.leafIndex(), request.treeSize(),
                proof, rootHash);
    }

    /**
     * 本地验证一致性证明。
     */
    public boolean verifyConsistency(VerifyConsistencyRequest request) {
        byte[] firstRoot = decodeHex(request.firstRootHash(), "firstRootHash");
        byte[] secondRoot = decodeHex(request.secondRootHash(), "secondRootHash");
        List<byte[]> proof = decodeHexList(request.proof());
        return MerkleTree.verifyConsistency(request.firstTreeSize(), request.secondTreeSize(),
                firstRoot, secondRoot, proof);
    }

    /**
     * 处理观察者签名树头上报：验签、单调性检查、分裂视图检测与告警原子去重。
     */
    @Transactional
    public ObserveResponse observe(ObserveRequest request) {
        String rootHash = request.rootHash().toLowerCase();
        validateRootHash(rootHash);
        byte[] signature = decodeHex(request.signature(), "signature");
        if (request.treeSize() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TREE_SIZE",
                    "treeSize must be >= 1");
        }
        byte[] message = canonicalObserveMessage(request.observerId(), request.treeSize(),
                rootHash, request.timestamp());
        if (!ed25519.verify(message, signature)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SIGNATURE",
                    "observer signature verification failed");
        }
        long current = repository.currentSize();
        if (request.treeSize() > current) {
            throw new ApiException(HttpStatus.CONFLICT, "TREE_SIZE_OUT_OF_RANGE",
                    "treeSize exceeds current log size");
        }

        ObserverHead head = repository.findObserverHeadForUpdate(request.observerId());
        if (head != null && request.treeSize() < head.treeSize()) {
            throw new ApiException(HttpStatus.CONFLICT, "OBSERVER_HEAD_REGRESSION",
                    "treeSize would regress the observer's latest position");
        }

        byte[] logRoot = MerkleTree.root(repository.leafHashesBetween(0, request.treeSize()));
        String logRootHex = HexFormat.of().formatHex(logRoot);
        boolean alertCreated = false;
        if (!logRootHex.equals(rootHash)) {
            String evidence = buildEvidence(request, rootHash, logRootHex);
            alertCreated = repository.insertAlertIfAbsent(new SplitAlert(null, "SPLIT_VIEW",
                    request.observerId(), request.treeSize(), logRootHex, rootHash, evidence, null));
        }

        Instant observedAt = Instant.ofEpochMilli(request.timestamp());
        if (head == null) {
            repository.insertObserverHead(new ObserverHead(request.observerId(), request.treeSize(),
                    rootHash, observedAt, request.signature(), null));
        } else if (request.treeSize() > head.treeSize()) {
            repository.advanceObserverHead(new ObserverHead(request.observerId(), request.treeSize(),
                    rootHash, observedAt, request.signature(), null));
        }
        long latest = head == null ? request.treeSize() : Math.max(head.treeSize(), request.treeSize());
        return new ObserveResponse(true, alertCreated, latest);
    }

    /**
     * 查询全部分裂视图告警。
     */
    @Transactional(readOnly = true)
    public List<SplitAlert> findAlerts() {
        return repository.findAlerts();
    }

    /**
     * 观察者上报的规范化签名消息。
     */
    public static byte[] canonicalObserveMessage(String observerId, long treeSize,
                                                 String rootHash, long timestamp) {
        return (SIGN_DOMAIN + "\n" + observerId + "\n" + treeSize + "\n" + rootHash + "\n" + timestamp)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private long resolveTreeSize(Long treeSize) {
        long current = repository.currentSize();
        long size = treeSize == null ? current : treeSize;
        if (size < 1 || size > current) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TREE_SIZE",
                    "treeSize must be in [1, current tree size]");
        }
        return size;
    }

    private static void validateRootHash(String rootHash) {
        if (rootHash.length() != 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "rootHash must be 64 hex characters");
        }
        decodeHex(rootHash, "rootHash");
    }

    private static String buildEvidence(ObserveRequest request, String observerRoot, String logRoot) {
        return "{\"observerId\":\"" + escape(request.observerId())
                + "\",\"treeSize\":" + request.treeSize()
                + ",\"observerRootHash\":\"" + observerRoot
                + "\",\"logRootHash\":\"" + logRoot
                + "\",\"timestamp\":" + request.timestamp()
                + ",\"signature\":\"" + escape(request.signature()) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[] decodeBase64(String data) {
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "data must be valid Base64");
        }
    }

    private static byte[] decodeHex(String hex, String field) {
        if (hex == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    field + " must not be null");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    field + " must be hex encoded");
        }
    }

    private static List<byte[]> decodeHexList(List<String> hexList) {
        if (hexList == null) {
            return List.of();
        }
        return hexList.stream().map(h -> decodeHex(h, "proof")).toList();
    }

    private static List<String> toHexList(List<byte[]> nodes) {
        return nodes.stream().map(n -> HexFormat.of().formatHex(n)).toList();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
