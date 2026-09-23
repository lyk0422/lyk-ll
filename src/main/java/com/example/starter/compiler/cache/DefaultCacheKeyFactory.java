package com.example.starter.compiler.cache;

import com.example.starter.compiler.api.DependencyInfo;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 默认缓存键工厂。
 *
 * <p>键要素（任一变化都会得到不同哈希）：
 * <ul>
 *   <li>入口相对工作目录的规范化路径；</li>
 *   <li>全部传递依赖（含入口）的相对路径与原始内容 SHA-256，按路径排序；</li>
 *   <li>规范化参数覆盖（按键字典序，UTF-8 字节拼接）。</li>
 * </ul>
 * 键计算不依赖时间、主机路径或请求标识，因此同一内容组合在任何机器上都得到相同键。
 */
@Component
public class DefaultCacheKeyFactory implements CacheKeyFactory {

    @Override
    public CacheKey create(String entryPath, List<DependencyInfo> dependencies, Map<String, String> parameters) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("entry=").append(entryPath).append('\n');
        canonical.append("dependencies:\n");
        dependencies.stream()
                .sorted((a, b) -> a.path().compareTo(b.path()))
                .forEach(dependency -> canonical.append(dependency.path())
                        .append('=').append(dependency.sha256()).append('\n'));
        canonical.append("parameters:\n");
        new TreeMap<>(parameters).forEach((key, value) -> canonical.append(key)
                .append('=').append(value == null ? "" : value).append('\n'));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return new CacheKey(HexFormat.of().formatHex(hash));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
