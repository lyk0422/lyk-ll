package com.example.starter.ct.web;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.ct.CtException;
import com.example.starter.ct.CtLogService;
import com.example.starter.ct.MerkleTree;

/**
 * 证书透明日志 API：追加叶、树头、包含/一致性证明及本地验证。
 */
@RestController
@RequestMapping("/api/ct")
@Validated
public class CtLogController {

    private static final HexFormat HEX = HexFormat.of();

    private final CtLogService ctLogService;

    public CtLogController(CtLogService ctLogService) {
        this.ctLogService = ctLogService;
    }

    /** 追加请求：derBase64 为 DER 字节的 Base64。 */
    public record AppendLeafRequest(@NotBlank String requestId, @NotBlank String derBase64) {
    }

    /** 追加响应。 */
    public record AppendLeafResponse(long leafIndex, long treeSize, String entryHash, String leafHash) {
    }

    /** 树头响应。 */
    public record TreeHeadResponse(long treeSize, String rootHash) {
    }

    /** 包含证明响应。 */
    public record InclusionProofResponse(long leafIndex, long treeSize, String leafHash, List<String> auditPath) {
    }

    /** 一致性证明响应。 */
    public record ConsistencyProofResponse(long first, long second, List<String> consistencyPath) {
    }

    /** 包含证明本地验证请求。 */
    public record InclusionVerifyRequest(@NotBlank String leafHash, @PositiveOrZero long leafIndex,
            @Positive long treeSize, @NotNull List<String> auditPath, @NotBlank String rootHash) {
    }

    /** 一致性证明本地验证请求。 */
    public record ConsistencyVerifyRequest(@Positive long first, @Positive long second, @NotBlank String firstRoot,
            @NotBlank String secondRoot, @NotNull List<String> consistencyPath) {
    }

    /** 验证结果响应。 */
    public record VerifyResponse(boolean valid) {
    }

    @PostMapping("/leaves")
    public AppendLeafResponse append(@RequestBody @Validated AppendLeafRequest request) {
        byte[] derBytes;
        try {
            derBytes = Base64.getDecoder().decode(request.derBase64());
        } catch (IllegalArgumentException e) {
            throw CtException.badRequest("INVALID_DER_ENCODING", "derBase64 不是合法 Base64");
        }
        var result = ctLogService.append(request.requestId(), derBytes);
        return new AppendLeafResponse(result.leafIndex(), result.treeSize(), result.entryHash(), result.leafHash());
    }

    @GetMapping("/tree-head")
    public TreeHeadResponse treeHead(@RequestParam(required = false) Long treeSize) {
        var head = ctLogService.treeHead(treeSize);
        return new TreeHeadResponse(head.treeSize(), head.rootHash());
    }

    @GetMapping("/proofs/inclusion")
    public InclusionProofResponse inclusionProof(@RequestParam long leafIndex,
            @RequestParam(required = false) Long treeSize) {
        var proof = ctLogService.inclusionProof(leafIndex, treeSize);
        return new InclusionProofResponse(proof.leafIndex(), proof.treeSize(), proof.leafHash(), proof.auditPath());
    }

    @GetMapping("/proofs/consistency")
    public ConsistencyProofResponse consistencyProof(@RequestParam long first, @RequestParam long second) {
        var proof = ctLogService.consistencyProof(first, second);
        return new ConsistencyProofResponse(proof.first(), proof.second(), proof.consistencyPath());
    }

    @PostMapping("/proofs/inclusion/verify")
    public VerifyResponse verifyInclusion(@RequestBody @Validated InclusionVerifyRequest request) {
        try {
            byte[] computed = MerkleTree.verifyInclusion(HEX.parseHex(request.leafHash()), request.leafIndex(),
                    request.treeSize(), parseHexList(request.auditPath()));
            return new VerifyResponse(HEX.formatHex(computed).equalsIgnoreCase(request.rootHash()));
        } catch (IllegalArgumentException e) {
            return new VerifyResponse(false);
        }
    }

    @PostMapping("/proofs/consistency/verify")
    public VerifyResponse verifyConsistency(@RequestBody @Validated ConsistencyVerifyRequest request) {
        try {
            boolean valid = MerkleTree.verifyConsistency(request.first(), request.second(),
                    HEX.parseHex(request.firstRoot()), HEX.parseHex(request.secondRoot()),
                    parseHexList(request.consistencyPath()));
            return new VerifyResponse(valid);
        } catch (IllegalArgumentException e) {
            return new VerifyResponse(false);
        }
    }

    private static List<byte[]> parseHexList(List<String> hexList) {
        return hexList.stream().map(HEX::parseHex).toList();
    }
}
