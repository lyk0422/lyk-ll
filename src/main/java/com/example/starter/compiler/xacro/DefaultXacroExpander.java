package com.example.starter.compiler.xacro;

import com.example.starter.compiler.api.DependencyInfo;
import com.example.starter.compiler.core.CompilerException;
import com.example.starter.compiler.core.ErrorCode;
import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受限 Xacro 方言展开器。
 *
 * <p>支持的构造：
 * <ul>
 *   <li>{@code <xacro:include filename="..."/>}：相对当前文件解析，内容内联到标签位置；</li>
 *   <li>{@code <xacro:property name="..." value="..."/>}：全局属性，值可引用其它已定义属性；</li>
 *   <li>文本与属性值中的 {@code ${name}} 替换。</li>
 * </ul>
 *
 * <p>其它任何 {@code xacro:*} 标签或属性（宏、条件、arg、scope 等）均返回
 * {@link ErrorCode#UNSUPPORTED_XACRO}，不静默忽略。请求参数优先于文件默认值；
 * 未定义属性返回 400；越界/缺失文件返回 400；循环 include 返回 422。
 */
@Component
public class DefaultXacroExpander implements XacroExpander {

    private static final String XACRO_PREFIX = "xacro:";
    private static final Pattern PARAMETER_PATTERN = Pattern.compile("\\$\\{([^}${}]*)}");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DocumentBuilder documentBuilder;
    private final Transformer transformer;

    public DefaultXacroExpander() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // 受控文件也禁止 DOCTYPE/外部实体，避免 XXE。
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            this.documentBuilder = factory.newDocumentBuilder();
            this.transformer = TransformerFactory.newInstance().newTransformer();
            this.transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IllegalStateException("XML 处理器初始化失败", e);
        }
    }

    @Override
    public ExpansionResult expand(Path workspaceRoot, Path entryFile, Map<String, String> parameters) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path entry = entryFile.toAbsolutePath().normalize();
        ensureInsideWorkspace(root, entry);
        if (!Files.isRegularFile(entry)) {
            throw new CompilerException(ErrorCode.BAD_REQUEST, "入口文件不存在: " + relativePath(root, entry));
        }

        Context context = new Context(root, new LinkedHashMap<>(parameters));
        Document document = processFile(entry, context);
        removeComments(document);

        // 统一替换 ${name}：文件属性值惰性求值，请求参数为最终字面值。
        String expanded = serialize(document);
        expanded = substituteParameters(expanded, context);

        List<DependencyInfo> dependencies = new ArrayList<>();
        context.dependencies.forEach((path, digest) ->
                dependencies.add(new DependencyInfo(relativePath(root, path), digest)));
        dependencies.sort((a, b) -> a.path().compareTo(b.path()));

        List<String> chain = new ArrayList<>();
        for (Path path : context.chain) {
            chain.add(relativePath(root, path));
        }
        return new ExpansionResult(expanded, dependencies, chain);
    }

    /**
     * 解析单个文件：解析 XML、内联 include、收集并移除 property、拒绝其它 xacro 构造。
     */
    private Document processFile(Path file, Context context) {
        if (context.stack.contains(file)) {
            throw cycleError(file, context);
        }
        byte[] bytes = readFile(file);
        context.visit(file, sha256(bytes));

        Document document;
        try {
            synchronized (documentBuilder) {
                document = documentBuilder.parse(new ByteArrayInputStream(bytes));
            }
        } catch (Exception e) {
            throw new CompilerException(ErrorCode.BAD_REQUEST,
                    "文件不是合法 XML: " + relativePath(context.workspaceRoot, file));
        }

        context.stack.addLast(file);
        try {
            processChildren(document.getDocumentElement(), file, context);
            document.getDocumentElement().removeAttribute("xmlns:xacro");
        } finally {
            context.stack.removeLast();
        }
        return document;
    }

    /**
     * 深度优先处理元素的子节点（include 内联会改变子节点集合，故用索引动态遍历）。
     */
    private void processChildren(Element parent, Path currentFile, Context context) {
        // 不直接遍历 parent.getChildNodes() 的快照：include 会就地插入多个节点。
        int index = 0;
        while (index < parent.getChildNodes().getLength()) {
            Node child = parent.getChildNodes().item(index);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                index++;
                continue;
            }
            Element element = (Element) child;
            String tagName = element.getTagName();

            if (tagName.startsWith(XACRO_PREFIX)) {
                String local = tagName.substring(XACRO_PREFIX.length());
                switch (local) {
                    case "include" -> {
                        inlineInclude(element, currentFile, context, parent, index);
                        // 不递增 index：刚插入的节点需要继续被处理（可能含 property 等）。
                        continue;
                    }
                    case "property" -> {
                        recordProperty(element, context);
                        parent.removeChild(element);
                        continue;
                    }
                    default -> throw new CompilerException(ErrorCode.UNSUPPORTED_XACRO,
                            "不支持的 Xacro 构造 <" + tagName + ">，受限方言仅允许 include 与 property");
                }
            }

            rejectXacroAttributes(element);
            processChildren(element, currentFile, context);
            index++;
        }
    }

    /**
     * 内联 xacro:include：把目标文件根元素的子节点导入当前文档并替换 include 标签。
     */
    private void inlineInclude(Element includeElement, Path currentFile, Context context,
                               Element parent, int includeIndex) {
        if (nonNamespaceAttributeCount(includeElement) != 1 || !includeElement.hasAttribute("filename")) {
            throw new CompilerException(ErrorCode.UNSUPPORTED_XACRO,
                    "xacro:include 仅支持 filename 属性，其它属性属于不支持的构造");
        }
        String filename = includeElement.getAttribute("filename").trim();
        if (filename.isEmpty()) {
            throw new CompilerException(ErrorCode.BAD_REQUEST,
                    "xacro:include 缺少 filename: " + relativePath(context.workspaceRoot, currentFile));
        }
        Path target = currentFile.getParent().resolve(filename).normalize();
        ensureInsideWorkspace(context.workspaceRoot, target);
        if (!Files.isRegularFile(target)) {
            throw new CompilerException(ErrorCode.BAD_REQUEST,
                    "include 引用的文件不存在: " + relativePath(context.workspaceRoot, target));
        }
        if (context.stack.contains(target)) {
            throw cycleError(target, context);
        }

        Document included = processFile(target, context);
        NodeList includedChildren = included.getDocumentElement().getChildNodes();
        List<Node> imported = new ArrayList<>();
        for (int i = 0; i < includedChildren.getLength(); i++) {
            imported.add(includeElement.getOwnerDocument().importNode(includedChildren.item(i), true));
        }
        for (Node node : imported) {
            parent.insertBefore(node, includeElement);
        }
        parent.removeChild(includeElement);
        // 插入节点数可能为 0（空 include），此时当前索引自然指向下一个兄弟。
        if (imported.isEmpty()) {
            // includeIndex 保持不变即可；占位使分支语义明确。
            return;
        }
    }

    /**
     * 记录文件定义的属性默认值（后定义覆盖文件内先定义，最终再由请求参数覆盖）。
     */
    private void recordProperty(Element element, Context context) {
        if (!element.hasAttribute("name") || !element.hasAttribute("value")
                || nonNamespaceAttributeCount(element) != 2) {
            throw new CompilerException(ErrorCode.UNSUPPORTED_XACRO,
                    "xacro:property 仅支持 name/value 属性，scope 等其它构造不受支持");
        }
        String name = element.getAttribute("name").trim();
        if (name.isEmpty()) {
            throw new CompilerException(ErrorCode.BAD_REQUEST, "xacro:property 的 name 不能为空");
        }
        context.fileProperties.put(name, element.getAttribute("value"));
    }

    private void rejectXacroAttributes(Element element) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Attr attr = (Attr) element.getAttributes().item(i);
            if (attr.getName().startsWith(XACRO_PREFIX)) {
                throw new CompilerException(ErrorCode.UNSUPPORTED_XACRO,
                        "不支持的 Xacro 属性 " + attr.getName() + "（宏调用等构造不受支持）");
            }
        }
    }

    /**
     * 替换展开文本中的全部 {@code ${name}}；未定义或属性间循环引用返回 400，
     * 非纯属性名表达式（算术、函数调用等）返回 UNSUPPORTED_XACRO。
     */
    private String substituteParameters(String text, Context context) {
        // 文件内容中残留的 ${ 一律视为未正确闭合表达式（替换后无法区分来源，故先检查）。
        rejectMalformedExpression(text);
        Map<String, String> resolved = new LinkedHashMap<>();
        Deque<String> resolving = new ArrayDeque<>();
        return substituteOnce(text, context, resolved, resolving);
    }

    private void rejectMalformedExpression(String text) {
        String stripped = PARAMETER_PATTERN.matcher(text).replaceAll("");
        int index = stripped.indexOf("${");
        if (index >= 0) {
            int start = Math.max(0, index - 20);
            int end = Math.min(stripped.length(), index + 40);
            throw new CompilerException(ErrorCode.BAD_REQUEST,
                    "存在未正确闭合的参数表达式: " + stripped.substring(start, end));
        }
    }

    private String substituteOnce(String text, Context context, Map<String, String> resolved,
                                  Deque<String> resolving) {
        Matcher matcher = PARAMETER_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!IDENTIFIER_PATTERN.matcher(name).matches()) {
                throw new CompilerException(ErrorCode.UNSUPPORTED_XACRO,
                        "受限方言仅支持 ${属性名} 替换，不支持表达式: ${" + name + "}");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolveValue(name, context, resolved, resolving)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String resolveValue(String name, Context context, Map<String, String> resolved, Deque<String> resolving) {
        if (resolved.containsKey(name)) {
            return resolved.get(name);
        }
        if (context.requestParameters.containsKey(name)) {
            // 请求参数为最终字面量，不再对其值做参数替换。
            String value = context.requestParameters.get(name);
            resolved.put(name, value);
            return value;
        }
        String raw = context.fileProperties.get(name);
        if (raw == null) {
            throw new CompilerException(ErrorCode.UNDEFINED_PARAMETER, "未定义的参数: ${" + name + "}");
        }
        if (resolving.contains(name)) {
            throw new CompilerException(ErrorCode.BAD_REQUEST, "属性定义存在循环引用: " + name);
        }
        rejectMalformedExpression(raw);
        resolving.addLast(name);
        String value;
        try {
            value = substituteOnce(raw, context, resolved, resolving);
        } finally {
            resolving.removeLast();
        }
        resolved.put(name, value);
        return value;
    }

    /**
     * 移除全部注释：Xacro 不在注释中做替换，且规范输出不保留注释，
     * 因此注释中的 ${...} 既不应被求值，也不应进入结果字节。
     */
    private void removeComments(Node node) {
        NodeList children = node.getChildNodes();
        List<Node> comments = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.COMMENT_NODE) {
                comments.add(child);
            } else {
                removeComments(child);
            }
        }
        comments.forEach(node::removeChild);
    }

    private String serialize(Document document) {
        try {
            StringWriter writer = new StringWriter();
            synchronized (transformer) {
                transformer.transform(new DOMSource(document), new StreamResult(writer));
            }
            return writer.toString();
        } catch (TransformerException e) {
            throw new CompilerException(ErrorCode.BAD_REQUEST, "展开结果序列化失败: " + e.getMessage());
        }
    }

    private byte[] readFile(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new CompilerException(ErrorCode.BAD_REQUEST, "无法读取文件: " + file);
        }
    }

    /**
     * 越界（含 {@code ..} 逃逸与绝对路径逃逸）返回 400。
     */
    private void ensureInsideWorkspace(Path workspaceRoot, Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new CompilerException(ErrorCode.PATH_OUTSIDE_WORKSPACE,
                    "文件引用超出受控工作目录: " + normalized);
        }
    }

    private CompilerException cycleError(Path repeated, Context context) {
        List<String> chain = new ArrayList<>();
        for (Path path : context.stack) {
            chain.add(relativePath(context.workspaceRoot, path));
        }
        chain.add(relativePath(context.workspaceRoot, repeated));
        throw new CompilerException(ErrorCode.CYCLIC_INCLUDE,
                "检测到循环 include，展开链路: " + String.join(" -> ", chain));
    }

    private String relativePath(Path workspaceRoot, Path file) {
        String relative = workspaceRoot.relativize(file.toAbsolutePath().normalize()).toString();
        return relative.replace('\\', '/');
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 统计非 XML 命名空间声明（xmlns:*）的属性数。
     */
    private int nonNamespaceAttributeCount(Element element) {
        int count = 0;
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Attr attr = (Attr) element.getAttributes().item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 单次展开的可变上下文。
     */
    private static final class Context {
        private final Path workspaceRoot;
        private final Map<String, String> requestParameters;
        /** 文件定义的属性默认值（原始、未求值），按出现顺序，后定义覆盖先定义。 */
        private final Map<String, String> fileProperties = new LinkedHashMap<>();
        /** 依赖摘要：绝对路径 -> 原始字节 SHA-256，按首次访问顺序。 */
        private final Map<Path, String> dependencies = new LinkedHashMap<>();
        /** include DFS 首次访问顺序（入口在首）。 */
        private final List<Path> chain = new ArrayList<>();
        /** 当前 include 深度栈，用于循环检测。 */
        private final Deque<Path> stack = new ArrayDeque<>();

        private Context(Path workspaceRoot, Map<String, String> requestParameters) {
            this.workspaceRoot = workspaceRoot;
            this.requestParameters = requestParameters;
        }

        private void visit(Path file, String digest) {
            if (!dependencies.containsKey(file)) {
                dependencies.put(file, digest);
                chain.add(file);
            }
        }
    }
}
