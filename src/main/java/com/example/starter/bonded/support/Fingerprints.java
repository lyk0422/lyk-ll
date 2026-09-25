package com.example.starter.bonded.support;

import com.example.starter.bonded.dto.ApplyTransferRequest;
import com.example.starter.bonded.dto.ItemRequest;
import com.example.starter.bonded.dto.VerifyItemRequest;
import com.example.starter.bonded.dto.VerifyRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段；多商品项按税号排序后规范化，字段顺序与数量单位固定。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 申请指纹：requestId + 企业 + 起运仓 + 目的仓 + 商品项（按税号排序，数量与单价规范化）。
     */
    public static String ofApply(ApplyTransferRequest req) {
        String items = req.items().stream()
                .sorted(Comparator.comparing(ItemRequest::hsCode))
                .map(i -> i.hsCode() + ":" + i.quantity() + ":" + i.unitPrice().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
        return sha256(String.join("|",
                "apply", req.requestId(), req.enterpriseId(), req.fromWarehouse(), req.toWarehouse(), items));
    }

    /**
     * 核销指纹：batchRequestId + 目标单据 requestId + 商品项（按税号排序）。
     */
    public static String ofVerify(String orderRequestId, VerifyRequest req) {
        String items = req.items().stream()
                .sorted(Comparator.comparing(VerifyItemRequest::hsCode))
                .map(i -> i.hsCode() + ":" + i.quantity())
                .collect(Collectors.joining(","));
        return sha256(String.join("|", "verify", req.batchRequestId(), orderRequestId, items));
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 合并同一税号的多行申请项（数量累加，单价须一致，否则视为非法请求）。
     */
    public static List<ItemRequest> mergeItems(List<ItemRequest> items) {
        return items.stream()
                .collect(Collectors.groupingBy(ItemRequest::hsCode))
                .entrySet().stream()
                .map(e -> {
                    long qty = e.getValue().stream().mapToLong(ItemRequest::quantity).sum();
                    var price = e.getValue().get(0).unitPrice();
                    boolean samePrice = e.getValue().stream()
                            .allMatch(i -> i.unitPrice().compareTo(price) == 0);
                    if (!samePrice) {
                        throw new IllegalArgumentException("同一税号 " + e.getKey() + " 出现不同单价");
                    }
                    return new ItemRequest(e.getKey(), qty, price);
                })
                .sorted(Comparator.comparing(ItemRequest::hsCode))
                .toList();
    }
}
