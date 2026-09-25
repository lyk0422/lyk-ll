package com.example.starter.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import com.example.starter.transfer.TransferDtos.ApplyRequest;
import com.example.starter.transfer.TransferDtos.ItemRequest;
import com.example.starter.transfer.TransferDtos.VerifyItemRequest;
import com.example.starter.transfer.TransferDtos.VerifyRequest;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段；多商品项按税号排序后规范化，
 * 保证同键同参指纹一致、同键异参指纹不同。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String ofApply(ApplyRequest req) {
        String items = req.items().stream()
                .sorted(Comparator.comparing(ItemRequest::tariffCode)
                        .thenComparingLong(ItemRequest::quantity))
                .map(i -> i.tariffCode() + ":" + i.quantity() + ":"
                        + i.unitPrice().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
        return sha256(String.join("|", req.requestId(), req.enterpriseId(),
                req.fromWarehouse(), req.toWarehouse(), items));
    }

    public static String ofVerify(long orderId, VerifyRequest req) {
        String items = req.items().stream()
                .sorted(Comparator.comparing(VerifyItemRequest::tariffCode)
                        .thenComparingLong(VerifyItemRequest::quantity))
                .map(i -> i.tariffCode() + ":" + i.quantity())
                .collect(Collectors.joining(","));
        return sha256(req.requestId() + "|" + orderId + "|" + items);
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 供测试与排序复用：按税号排序的商品项比较器。 */
    static <T> List<T> sortedByTariff(List<T> items, java.util.function.Function<T, String> tariff) {
        return items.stream().sorted(Comparator.comparing(tariff)).toList();
    }
}
