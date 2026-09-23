package com.example.starter.compiler.xacro;

import java.nio.file.Path;
import java.util.Map;

/**
 * 受限 Xacro 方言展开器：支持 xacro:include、xacro:property 与 ${name} 替换。
 */
public interface XacroExpander {

    /**
     * 从入口文件开始展开。
     *
     * @param workspaceRoot 受控工作目录根（所有引用必须位于其内部）
     * @param entryFile     已解析到工作目录内的入口文件
     * @param parameters    请求级参数覆盖（优先于文件默认值）
     * @return 展开结果
     */
    ExpansionResult expand(Path workspaceRoot, Path entryFile, Map<String, String> parameters);
}
