package com.example.starter.robot;

import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 受限 Xacro 展开器：支持 xacro:include、xacro:property 与 ${name} 参数替换。
 *
 * <p>处理模型：按文档顺序单遍交错执行——遇到 xacro:property 登记文件默认值
 * （请求参数优先，默认值只填充未被覆盖的键）；遇到 xacro:include 递归展开并将
 * 被包含文件根节点下的子元素就地合入；其余元素做 ${name} 文本与属性替换后深拷贝。
 * 受限子集之外的 Xacro 构造一律抛出 {@link ErrorCode#UNSUPPORTED_XACRO}。
 */
@Component
public class XacroExpander {

    /** Xacro 命名空间 URI；解析器会把 xacro 前缀映射到该 URI。 */
    private static final String XACRO_NS = "http://www.ros.org/wiki/xacro";
    /** XML 命名空间声明属性所在的保留命名空间。 */
    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";
    /** 属性值中嵌套参数引用的最大解析深度，防止自引用无限展开。 */
    private static final int MAX_VALUE_EXPANSION_DEPTH = 32;
    /** 参数名语法：字母/下划线开头，后接字母、数字、下划线。 */
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern PARAMETER_TOKEN = Pattern.compile("\\$\\{([^}]*)}");

    private final DocumentBuilder documentBuilder;

    public XacroExpander() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            this.documentBuilder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 解析器初始化失败", e);
        }
    }

    /**
     * 从入口文件开始展开。
     *
     * @param workspaceRoot 受控工作目录根
     * @param entryRelative 入口相对路径（POSIX 风格）
     * @param overrides     请求参数覆盖
     * @return 展开结果
     */
    public ExpansionResult expand(Path workspaceRoot, String entryRelative, Map<String, String> overrides) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path entryPath = resolveInWorkspace(root, null, entryRelative, true);

        Context ctx = new Context(root);
        // 请求参数覆盖作为初始有效参数，文件默认值不得覆盖它们
        overrides.forEach((k, v) -> ctx.parameters.put(k, v == null ? "" : v));

        Document out = documentBuilder.newDocument();
        LoadedFile entry = ctx.load(entryPath);
        Element entryRoot = entry.document().getDocumentElement();
        rejectForeignRootAttributes(entryRoot, entry.relativePath());
        Element outRoot = (Element) out.importNode(entryRoot, false);
        out.appendChild(outRoot);
        substituteAttributes(outRoot, ctx, entry.relativePath());

        List<String> stack = new ArrayList<>(List.of(entry.relativePath()));
        processChildren(entry, outRoot, ctx, stack);

        String urdf = UrdfXml.serialize(out);
        Map<String, String> effectiveParameters = new TreeMap<>(ctx.parameters);
        List<DependencyInfo> dependencies = ctx.dependencyList(entry.relativePath());
        return new ExpansionResult(urdf, dependencies, List.copyOf(ctx.chain), effectiveParameters);
    }

    /**
     * 将文件根节点下的顶层子节点按文档顺序处理：property 登记、include 就地合入、
     * 其余元素替换参数后复制到目标父节点。
     */
    private void processChildren(LoadedFile file, Element destParent, Context ctx, List<String> stack) {
        Document destDoc = destParent.getOwnerDocument();
        for (Element child : childElements(file.document().getDocumentElement())) {
            String ns = child.getNamespaceURI();
            String local = child.getLocalName();
            if (XACRO_NS.equals(ns) && "property".equals(local)) {
                registerProperty(child, ctx, file.relativePath());
            } else if (XACRO_NS.equals(ns) && "include".equals(local)) {
                LoadedFile included = processInclude(child, ctx, stack, file);
                try {
                    processChildren(included, destParent, ctx, stack);
                } finally {
                    stack.remove(stack.size() - 1);
                }
            } else {
                rejectUnsupportedElement(child, file.relativePath());
                Element copy = (Element) destDoc.importNode(child, false);
                substituteAttributes(copy, ctx, file.relativePath());
                destParent.appendChild(copy);
                copyChildren(child, copy, ctx, file.relativePath());
            }
        }
    }

    /** 深拷贝普通元素的子节点，并对属性外的文本做参数替换。 */
    private void copyChildren(Element srcParent, Element destParent, Context ctx, String fileRel) {
        NodeList children = srcParent.getChildNodes();
        Document destDoc = destParent.getOwnerDocument();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            switch (node.getNodeType()) {
                case Node.ELEMENT_NODE -> {
                    rejectUnsupportedElement((Element) node, fileRel);
                    Element copy = (Element) destDoc.importNode(node, false);
                    substituteAttributes(copy, ctx, fileRel);
                    destParent.appendChild(copy);
                    copyChildren((Element) node, copy, ctx, fileRel);
                }
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    String text = node.getNodeValue();
                    if (text.isBlank()) {
                        // 纯空白文本交给最终规范化缩进，避免合入后产生不稳定空白
                        continue;
                    }
                    destParent.appendChild(destDoc.createTextNode(substituteText(text, ctx, fileRel, 0)));
                }
                default -> {
                    // 注释、处理指令等不进入规范 URDF 输出
                }
            }
        }
    }

    /** 登记 xacro:property 的文件默认值；已存在（请求覆盖或先前默认）时不覆盖。 */
    private void registerProperty(Element property, Context ctx, String fileRel) {
        NamedNodeMap attrs = property.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            if (XMLNS_NS.equals(attr.getNamespaceURI())) {
                continue;
            }
            if (attr.getNamespaceURI() != null && !attr.getNamespaceURI().isEmpty()) {
                throw unsupported("不支持的 xacro:property 属性: " + attr.getName(), fileRel);
            }
            if (!"name".equals(attr.getName()) && !"value".equals(attr.getName())) {
                throw unsupported("xacro:property 仅支持 name/value，不支持属性: " + attr.getName(), fileRel);
            }
        }
        if (property.hasChildNodes()) {
            throw unsupported("不支持块级 xacro:property，仅支持 name/value 标量属性", fileRel);
        }
        String name = property.getAttribute("name");
        String value = property.getAttribute("value");
        if (name.isEmpty() || !PARAMETER_NAME.matcher(name).matches()) {
            throw unsupported("xacro:property 缺少合法 name 属性", fileRel);
        }
        if (!property.hasAttribute("value")) {
            throw unsupported("xacro:property 必须声明 value 属性", fileRel);
        }
        ctx.parameters.putIfAbsent(name, value);
    }

    /** 处理 xacro:include：校验、参数替换、越界检查、循环检查与加载。 */
    private LoadedFile processInclude(Element include, Context ctx, List<String> stack, LoadedFile from) {
        NamedNodeMap attrs = include.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            if (XMLNS_NS.equals(attr.getNamespaceURI())) {
                continue;
            }
            if (attr.getNamespaceURI() != null && !attr.getNamespaceURI().isEmpty()) {
                throw unsupported("不支持的 xacro:include 属性: " + attr.getName(), from.relativePath());
            }
            if (!"filename".equals(attr.getName())) {
                throw unsupported("xacro:include 仅支持 filename，不支持属性: " + attr.getName(),
                        from.relativePath());
            }
        }
        if (include.hasChildNodes()) {
            throw unsupported("xacro:include 不允许包含子节点", from.relativePath());
        }
        String rawFilename = include.getAttribute("filename");
        if (rawFilename.isEmpty()) {
            throw unsupported("xacro:include 缺少 filename 属性", from.relativePath());
        }
        String filename = substituteText(rawFilename, ctx, from.relativePath(), 0);
        if (filename.contains("$(")) {
            throw unsupported("不支持 $(find ...) 等 ROS 路径替换: " + rawFilename, from.relativePath());
        }
        Path target = resolveInWorkspace(ctx.root, from.path().getParent(), filename, false);
        String targetRel = ctx.relative(target);
        if (stack.contains(targetRel)) {
            List<String> cycle = new ArrayList<>(stack);
            cycle.add(targetRel);
            throw new CompileException(ErrorCode.INCLUDE_CYCLE,
                    "检测到循环 include: " + String.join(" -> ", cycle), cycle);
        }
        LoadedFile loaded = ctx.load(target);
        rejectForeignRootAttributes(loaded.document().getDocumentElement(), targetRel);
        stack.add(targetRel);
        return loaded;
    }

    /**
     * 解析受控工作目录内的相对路径并校验越界；入口缺失返回 ENTRY_NOT_FOUND，
     * include 缺失返回 INCLUDE_NOT_FOUND，越界返回 INCLUDE_OUT_OF_BOUNDS。
     */
    private Path resolveInWorkspace(Path root, Path baseDir, String rawPath, boolean entry) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new CompileException(entry ? ErrorCode.ENTRY_NOT_FOUND : ErrorCode.INCLUDE_NOT_FOUND,
                    (entry ? "入口文件路径为空" : "include 路径为空"));
        }
        String unixPath = rawPath.replace('\\', '/');
        if (unixPath.startsWith("/") || unixPath.matches("[A-Za-z]:.*")) {
            throw new CompileException(ErrorCode.INCLUDE_OUT_OF_BOUNDS,
                    "不允许绝对路径引用: " + rawPath);
        }
        Path base = baseDir == null ? root : baseDir;
        Path resolved = base.resolve(unixPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new CompileException(ErrorCode.INCLUDE_OUT_OF_BOUNDS,
                    "文件引用越出受控工作目录: " + rawPath);
        }
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new CompileException(entry ? ErrorCode.ENTRY_NOT_FOUND : ErrorCode.INCLUDE_NOT_FOUND,
                    (entry ? "入口文件不存在: " : "include 文件不存在: ") + rawPath);
        }
        try {
            Path canonical = resolved.toRealPath();
            Path canonicalRoot = root.toRealPath();
            if (!canonical.startsWith(canonicalRoot)) {
                throw new CompileException(ErrorCode.INCLUDE_OUT_OF_BOUNDS,
                        "文件引用经符号链接越出受控工作目录: " + rawPath);
            }
            return canonical;
        } catch (IOException e) {
            throw new CompileException(entry ? ErrorCode.ENTRY_NOT_FOUND : ErrorCode.INCLUDE_NOT_FOUND,
                    "文件无法读取: " + rawPath);
        }
    }

    /** 入口/被包含文件根元素上除 xmlns 声明外的命名空间属性属于不支持构造。 */
    private void rejectForeignRootAttributes(Element root, String fileRel) {
        NamedNodeMap attrs = root.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            if (!XMLNS_NS.equals(attr.getNamespaceURI())
                    && attr.getNamespaceURI() != null && !attr.getNamespaceURI().isEmpty()) {
                throw unsupported("根元素 <" + root.getTagName() + "> 上不支持的属性 " + attr.getName(), fileRel);
            }
        }
    }

    /** 递归拒绝任何非 Xacro 受限元素或命名空间属性（include/property 由调用方先行处理）。 */
    private void rejectUnsupportedElement(Element element, String fileRel) {
        String ns = element.getNamespaceURI();
        if (ns != null && !ns.isEmpty()) {
            throw unsupported("不支持的命名空间构造 <" + element.getTagName() + ">", fileRel);
        }
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            if (!XMLNS_NS.equals(attr.getNamespaceURI())
                    && attr.getNamespaceURI() != null && !attr.getNamespaceURI().isEmpty()) {
                throw unsupported("元素 <" + element.getTagName() + "> 上不支持的属性 " + attr.getName(), fileRel);
            }
        }
    }

    /** 替换元素全部普通属性值中的 ${name}，并剥离 xmlns 声明。 */
    private void substituteAttributes(Element element, Context ctx, String fileRel) {
        NamedNodeMap attrs = element.getAttributes();
        List<Attr> namespaced = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            if (XMLNS_NS.equals(attr.getNamespaceURI())) {
                namespaced.add(attr);
            } else if (attr.getNamespaceURI() == null || attr.getNamespaceURI().isEmpty()) {
                attr.setValue(substituteText(attr.getValue(), ctx, fileRel, 0));
            }
        }
        namespaced.forEach(element::removeAttributeNode);
    }

    /** 替换文本中的全部 ${name}，并递归解析替换值内的嵌套引用。 */
    private String substituteText(String text, Context ctx, String fileRel, int depth) {
        if (text.indexOf("${") < 0 || depth > MAX_VALUE_EXPANSION_DEPTH) {
            if (depth > MAX_VALUE_EXPANSION_DEPTH) {
                throw unsupported("参数嵌套引用过深，可能存在循环引用", fileRel);
            }
            return text;
        }
        java.util.regex.Matcher matcher = PARAMETER_TOKEN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            if (!PARAMETER_NAME.matcher(expression).matches()) {
                throw unsupported("仅支持 ${参数名} 替换，不支持表达式: ${" + matcher.group(1) + "}", fileRel);
            }
            String value = ctx.parameters.get(expression);
            if (value == null) {
                throw new CompileException(ErrorCode.UNDEFINED_PARAMETER,
                        "未定义的参数: " + expression + "（文件 " + fileRel + "）");
            }
            matcher.appendReplacement(result,
                    java.util.regex.Matcher.quoteReplacement(
                            substituteText(value, ctx, fileRel, depth + 1)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private CompileException unsupported(String message, String fileRel) {
        return new CompileException(ErrorCode.UNSUPPORTED_XACRO, message + "（文件 " + fileRel + "）");
    }

    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) nodes.item(i));
            }
        }
        return elements;
    }

    /** 已加载文件：物理路径、相对路径、原始字节摘要与解析文档。 */
    private record LoadedFile(Path path, String relativePath, String sha256, Document document) {
    }

    /** 单次展开上下文：参数表、依赖登记与展开链路。 */
    private final class Context {
        private final Path root;
        private final Map<String, String> parameters = new LinkedHashMap<>();
        private final Map<String, LoadedFile> loaded = new LinkedHashMap<>();
        private final List<String> chain = new ArrayList<>();

        private Context(Path root) {
            this.root = root;
        }

        private LoadedFile load(Path path) {
            String rel = relative(path);
            LoadedFile existing = loaded.get(rel);
            if (existing != null) {
                return existing;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(path);
            } catch (IOException e) {
                throw new CompileException(ErrorCode.INCLUDE_NOT_FOUND, "文件无法读取: " + rel);
            }
            Document document;
            try {
                synchronized (documentBuilder) {
                    document = documentBuilder.parse(new ByteArrayInputStream(bytes));
                }
            } catch (SAXException e) {
                throw new CompileException(ErrorCode.INVALID_REQUEST,
                        "XML 解析失败（文件 " + rel + "）: " + e.getMessage());
            } catch (IOException e) {
                throw new CompileException(ErrorCode.INCLUDE_NOT_FOUND, "文件无法读取: " + rel);
            }
            LoadedFile file = new LoadedFile(path, rel, Digests.sha256Hex(bytes), document);
            loaded.put(rel, file);
            chain.add(rel);
            return file;
        }

        private List<DependencyInfo> dependencyList(String entryRelative) {
            return loaded.keySet().stream()
                    .sorted()
                    .map(rel -> new DependencyInfo(rel, loaded.get(rel).sha256(), rel.equals(entryRelative)))
                    .toList();
        }

        private String relative(Path path) {
            String rel = root.relativize(path).toString().replace('\\', '/');
            return rel.isEmpty() ? "." : rel;
        }
    }
}
