package com.example.starter.ctlog;

import com.example.starter.ctlog.crypto.DemoObserverKeys;
import com.example.starter.ctlog.merkle.MerkleTree;
import com.example.starter.ctlog.service.CtLogService;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端合成演示场景：追加叶、树头、包含证明验证、触发分裂视图告警。
 *
 * <p>该测试同时把可直接执行的 curl 示例（含确定性 Ed25519 签名）打印到
 * 标准输出，供本地演示与文档引用；所有数据均为合成数据。</p>
 */
class DemoScenarioTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void demoScenario() throws Exception {
        // 1. 追加叶：合成 DER 字节。
        byte[] der = "synthetic-cert-der-01".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String dataBase64 = Base64.getEncoder().encodeToString(der);
        byte[] entryHash = MessageDigest.getInstance("SHA-256").digest(der);
        String entryHashHex = HEX.formatHex(entryHash);

        // 2. 单叶树的树头：root = SHA-256(0x00 || entryHash)。
        byte[] root = MerkleTree.root(List.of(entryHash));
        String rootHex = HEX.formatHex(root);
        assertEquals(HEX.formatHex(MerkleTree.leafHash(entryHash)), rootHex);

        // 3. 单叶树索引 0 的包含证明为空路径，本地验证通过。
        List<byte[]> proof = MerkleTree.inclusionProof(List.of(entryHash), 0);
        assertTrue(proof.isEmpty());
        assertTrue(MerkleTree.verifyInclusion(MerkleTree.leafHash(entryHash), 0, 1, proof, root));

        // 4. 触发告警：观察者上报同一 treeSize=1 的不同根（合成假根），Ed25519 签名确定。
        String fakeRoot = HEX.formatHex(MerkleTree.leafHash(new byte[]{0x42}));
        long timestamp = 1_700_000_000_000L;
        byte[] message = CtLogService.canonicalObserveMessage("demo-observer", 1, fakeRoot, timestamp);
        String signature = TestSigning.signWithDemoKey(message);

        StringBuilder out = new StringBuilder();
        out.append("\n==== DEMO COMMANDS ====\n");
        out.append("# 1) 追加叶（合成 DER 的 Base64）\n");
        out.append("curl -s -X POST http://localhost:8080/ct/v1/add-leaf ")
                .append("-H 'Content-Type: application/json' ")
                .append("-d '{\"requestId\":\"demo-req-1\",\"data\":\"").append(dataBase64).append("\"}'\n");
        out.append("# 预期: {\"leafIndex\":0,\"entryHash\":\"").append(entryHashHex)
                .append("\",\"idempotent\":false}\n");
        out.append("# 2) 读取树头\n");
        out.append("curl -s http://localhost:8080/ct/v1/tree-head\n");
        out.append("# 预期: treeSize=1, rootHash=").append(rootHex).append("\n");
        out.append("# 3) 获取包含证明（单叶树 proof 为空）\n");
        out.append("curl -s 'http://localhost:8080/ct/v1/inclusion-proof?leafIndex=0'\n");
        out.append("# 4) 本地验证包含证明\n");
        out.append("curl -s -X POST http://localhost:8080/ct/v1/verify/inclusion ")
                .append("-H 'Content-Type: application/json' ")
                .append("-d '{\"leafHash\":\"").append(rootHex)
                .append("\",\"leafIndex\":0,\"treeSize\":1,\"rootHash\":\"").append(rootHex)
                .append("\",\"proof\":[]}'\n");
        out.append("# 预期: {\"valid\":true}\n");
        out.append("# 5) 观察者上报分歧根，触发 SPLIT_VIEW 告警\n");
        out.append("curl -s -X POST http://localhost:8080/ct/v1/observe ")
                .append("-H 'Content-Type: application/json' ")
                .append("-d '{\"observerId\":\"demo-observer\",\"treeSize\":1,\"rootHash\":\"")
                .append(fakeRoot).append("\",\"timestamp\":").append(timestamp)
                .append(",\"signature\":\"").append(signature).append("\"}'\n");
        out.append("# 预期: {\"accepted\":true,\"alertCreated\":true,\"latestTreeSize\":1}\n");
        out.append("# 6) 查询告警\n");
        out.append("curl -s http://localhost:8080/ct/v1/alerts\n");
        out.append("# 预期: alerts[0].alertType=SPLIT_VIEW, observerRootHash=").append(fakeRoot)
                .append(", logRootHash=").append(rootHex).append("\n");
        out.append("==== DEMO VALUES ====\n");
        out.append("DATA_BASE64=").append(dataBase64).append('\n');
        out.append("ENTRY_HASH=").append(entryHashHex).append('\n');
        out.append("ROOT=").append(rootHex).append('\n');
        out.append("FAKE_ROOT=").append(fakeRoot).append('\n');
        out.append("OBSERVE_SIGNATURE=").append(signature).append('\n');
        out.append("OBSERVER_PUBLIC_KEY=").append(DemoObserverKeys.PUBLIC_KEY_HEX).append('\n');
        System.out.println(out);
    }
}
