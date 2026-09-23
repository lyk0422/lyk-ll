package com.example.starter.compiler.urdf;

import com.example.starter.compiler.core.CompilerException;
import com.example.starter.compiler.core.ErrorCode;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 展开后 URDF 的结构与数值校验器。
 *
 * <p>校验项：
 * <ul>
 *   <li>根元素为 robot；Link 与 Joint 名称在各自集合及相互之间均唯一；</li>
 *   <li>恰有一个根 Link（未被任何 joint 作为 child 的 link）；</li>
 *   <li>每个 joint 的 parent/child 均引用已存在 link，且不形成关节环（运动链为树）；</li>
 *   <li>所有数值属性有限（origin/axis/limit/dynamics/mass/inertia 等）；</li>
 *   <li>惯量主对角项 ixx/iyy/izz 为正；</li>
 *   <li>joint 的 type 合法，revolute/prismatic 提供完整 limit；</li>
 *   <li>visual/collision 的 origin 坐标系字段完整且含合法 geometry。</li>
 * </ul>
 * 错误收集后一次性抛出，保证问题暴露完整且顺序稳定。
 */
@Component
public class DefaultUrdfValidator implements UrdfValidator {

    private static final Set<String> JOINT_TYPES =
            Set.of("revolute", "continuous", "prismatic", "fixed", "floating", "planar");
    private static final Set<String> GEOMETRY_TYPES = Set.of("box", "cylinder", "sphere", "mesh");

    private final DocumentBuilder documentBuilder;

    public DefaultUrdfValidator() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            this.documentBuilder = factory.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("XML 处理器初始化失败", e);
        }
    }

    @Override
    public void validate(String urdf) {
        Document document = parse(urdf);
        Element robot = document.getDocumentElement();
        List<String> errors = new ArrayList<>();

        if (!"robot".equals(robot.getTagName())) {
            throw new CompilerException(ErrorCode.INVALID_URDF, "URDF 根元素必须是 <robot>，实际为 <" + robot.getTagName() + ">");
        }
        if (!robot.hasAttribute("name") || robot.getAttribute("name").isBlank()) {
            errors.add("robot 元素必须包含非空 name 属性");
        }

        List<Element> links = childElements(robot, "link");
        List<Element> joints = childElements(robot, "joint");

        if (links.isEmpty()) {
            errors.add("URDF 至少需要一个 link");
        }

        validateUniqueNames(links, joints, errors);
        Map<String, Element> linksByName = indexLinks(links, errors);
        Map<String, Element> jointsByName = new LinkedHashMap<>();
        for (Element joint : joints) {
            jointsByName.put(joint.getAttribute("name"), joint);
        }

        // child link -> 父 link；树结构要求每个非根 link 恰有一个父关节且整图无环。
        Map<String, String> parentLinkByChild = new LinkedHashMap<>();
        // child link -> 指向它的 joint 名，用于多父节点报错。
        Map<String, String> parentJointByChild = new LinkedHashMap<>();
        validateJoints(joints, linksByName, parentLinkByChild, parentJointByChild, errors);
        validateRootAndTree(linksByName, parentLinkByChild, errors);

        for (Element link : links) {
            validateLink(link, errors);
        }

        if (!errors.isEmpty()) {
            throw new CompilerException(ErrorCode.INVALID_URDF, "URDF 校验失败: " + String.join("; ", errors));
        }
    }

    private Document parse(String urdf) {
        try {
            synchronized (documentBuilder) {
                return documentBuilder.parse(new ByteArrayInputStream(urdf.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            throw new CompilerException(ErrorCode.INVALID_URDF, "展开结果不是合法 XML: " + e.getMessage());
        }
    }

    private void validateUniqueNames(List<Element> links, List<Element> joints, List<String> errors) {
        Set<String> linkNames = new HashSet<>();
        for (Element link : links) {
            String name = requiredName(link, "link", errors);
            if (name != null && !linkNames.add(name)) {
                errors.add("link 名称重复: " + name);
            }
        }
        Set<String> jointNames = new HashSet<>();
        for (Element joint : joints) {
            String name = requiredName(joint, "joint", errors);
            if (name == null) {
                continue;
            }
            if (!jointNames.add(name)) {
                errors.add("joint 名称重复: " + name);
            }
            // link 与 joint 共享命名空间可避免父子引用歧义。
            if (linkNames.contains(name)) {
                errors.add("joint 名称与已有 link 名称冲突: " + name);
            }
        }
    }

    private Map<String, Element> indexLinks(List<Element> links, List<String> errors) {
        Map<String, Element> byName = new LinkedHashMap<>();
        for (Element link : links) {
            String name = link.getAttribute("name");
            if (!name.isBlank()) {
                byName.putIfAbsent(name, link);
            }
        }
        return byName;
    }

    private void validateJoints(List<Element> joints, Map<String, Element> linksByName,
                                Map<String, String> parentLinkByChild,
                                Map<String, String> parentJointByChild, List<String> errors) {
        for (Element joint : joints) {
            String jointName = joint.getAttribute("name");
            String type = joint.getAttribute("type");
            if (type.isBlank()) {
                errors.add(describe(jointName) + "缺少 type 属性");
            } else if (!JOINT_TYPES.contains(type)) {
                errors.add(describe(jointName) + "type 不合法: " + type);
            }

            Element parent = firstChildElement(joint, "parent");
            Element child = firstChildElement(joint, "child");
            String parentName = parent != null ? parent.getAttribute("link") : "";
            String childName = child != null ? child.getAttribute("link") : "";
            if (parent == null || parentName.isBlank()) {
                errors.add(describe(jointName) + "缺少 <parent link=\"...\"/>");
            } else if (!linksByName.containsKey(parentName)) {
                errors.add(describe(jointName) + "parent link 不存在: " + parentName);
            }
            if (child == null || childName.isBlank()) {
                errors.add(describe(jointName) + "缺少 <child link=\"...\"/>");
            } else if (!linksByName.containsKey(childName)) {
                errors.add(describe(jointName) + "child link 不存在: " + childName);
            }
            if (parentName.equals(childName) && !parentName.isBlank()) {
                errors.add(describe(jointName) + "parent 与 child 不能相同: " + parentName);
            }
            if (!childName.isBlank() && linksByName.containsKey(childName)
                    && linksByName.containsKey(parentName) && !parentName.equals(childName)) {
                String previous = parentJointByChild.put(childName, jointName);
                if (previous != null) {
                    errors.add("link " + childName + " 被多个 joint 作为 child: " + previous + ", " + jointName);
                } else {
                    parentLinkByChild.put(childName, parentName);
                }
            }

            Element origin = firstChildElement(joint, "origin");
            if (origin != null) {
                validateOrigin(origin, describe(jointName), errors);
            }
            Element axis = firstChildElement(joint, "axis");
            if (axis != null) {
                validateVectorAttribute(axis, "xyz", 3, describe(jointName) + "axis ", errors);
            }
            Element limit = firstChildElement(joint, "limit");
            validateLimit(limit, type, jointName, errors);
            Element dynamics = firstChildElement(joint, "dynamics");
            if (dynamics != null) {
                parseFiniteOptional(dynamics, "damping", describe(jointName) + "dynamics ", errors);
                parseFiniteOptional(dynamics, "friction", describe(jointName) + "dynamics ", errors);
            }
        }
    }

    private void validateLimit(Element limit, String type, String jointName, List<String> errors) {
        String prefix = describe(jointName) + "limit ";
        if ("revolute".equals(type) || "prismatic".equals(type)) {
            if (limit == null) {
                errors.add(describe(jointName) + type + " 关节必须声明 <limit>");
                return;
            }
            double effort = parseFiniteRequired(limit, "effort", prefix, errors);
            double velocity = parseFiniteRequired(limit, "velocity", prefix, errors);
            double lower = parseFiniteRequired(limit, "lower", prefix, errors);
            double upper = parseFiniteRequired(limit, "upper", prefix, errors);
            if (effort < 0) {
                errors.add(prefix + "effort 不能为负");
            }
            if (velocity < 0) {
                errors.add(prefix + "velocity 不能为负");
            }
            if (lower > upper) {
                errors.add(prefix + "lower 不能大于 upper");
            }
        } else if (limit != null) {
            parseFiniteOptional(limit, "effort", prefix, errors);
            parseFiniteOptional(limit, "velocity", prefix, errors);
            parseFiniteOptional(limit, "lower", prefix, errors);
            parseFiniteOptional(limit, "upper", prefix, errors);
        }
    }

    private void validateRootAndTree(Map<String, Element> linksByName, Map<String, String> parentLinkByChild,
                                     List<String> errors) {
        List<String> roots = new ArrayList<>();
        for (String linkName : linksByName.keySet()) {
            if (!parentLinkByChild.containsKey(linkName)) {
                roots.add(linkName);
            }
        }
        if (roots.size() != 1) {
            errors.add("必须恰有一个根 link，当前为 " + roots.size() + " 个: " + roots);
            return;
        }
        String root = roots.get(0);

        // 构造 parent -> children 邻接表，从唯一根遍历；单父节点约束下，
        // 未达节点意味着存在关节环（成环节点均有父，故不会出现在根集合中）或孤立子树。
        Map<String, List<String>> childrenByParent = new LinkedHashMap<>();
        for (String linkName : linksByName.keySet()) {
            childrenByParent.put(linkName, new ArrayList<>());
        }
        for (Map.Entry<String, String> edge : parentLinkByChild.entrySet()) {
            childrenByParent.getOrDefault(edge.getValue(), new ArrayList<>()).add(edge.getKey());
        }

        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) {
                errors.add("关节连接形成环，重复访问 link: " + current);
                return;
            }
            for (String child : childrenByParent.getOrDefault(current, List.of())) {
                stack.push(child);
            }
        }
        if (visited.size() != linksByName.size()) {
            List<String> unreachable = new ArrayList<>();
            for (String linkName : linksByName.keySet()) {
                if (!visited.contains(linkName)) {
                    unreachable.add(linkName);
                }
            }
            errors.add("存在从根 link " + root + " 不可达的 link，关节连接形成环或孤立子树: " + unreachable);
        }
    }

    private void validateLink(Element link, List<String> errors) {
        String linkName = link.getAttribute("name");

        Element inertial = firstChildElement(link, "inertial");
        if (inertial != null) {
            String prefix = "link " + linkName + " inertial ";
            Element inertialOrigin = firstChildElement(inertial, "origin");
            if (inertialOrigin != null) {
                validateOrigin(inertialOrigin, prefix, errors);
            }
            Element mass = firstChildElement(inertial, "mass");
            if (mass == null) {
                errors.add(prefix + "缺少 <mass>");
            } else {
                double massValue = parseFiniteRequired(mass, "value", prefix, errors);
                if (massValue < 0) {
                    errors.add(prefix + "mass 不能为负");
                }
            }
            Element inertia = firstChildElement(inertial, "inertia");
            if (inertia == null) {
                errors.add(prefix + "缺少 <inertia>");
            } else {
                double ixx = parseFiniteRequired(inertia, "ixx", prefix, errors);
                double iyy = parseFiniteRequired(inertia, "iyy", prefix, errors);
                double izz = parseFiniteRequired(inertia, "izz", prefix, errors);
                parseFiniteRequired(inertia, "ixy", prefix, errors);
                parseFiniteRequired(inertia, "ixz", prefix, errors);
                parseFiniteRequired(inertia, "iyz", prefix, errors);
                if (ixx <= 0 || iyy <= 0 || izz <= 0) {
                    errors.add(prefix + "惯量主对角项必须为正: ixx=" + ixx + ", iyy=" + iyy + ", izz=" + izz);
                }
            }
        }

        for (String frameTag : new String[]{"visual", "collision"}) {
            for (Element frame : childElements(link, frameTag)) {
                validateFrame(frame, linkName, errors);
            }
        }
    }

    private void validateFrame(Element frame, String linkName, List<String> errors) {
        String prefix = "link " + linkName + " " + frame.getTagName() + " ";
        // 若声明 origin，则 xyz/rpy 两个坐标系字段必须同时存在且为有限三元数。
        Element origin = firstChildElement(frame, "origin");
        if (origin != null) {
            validateOrigin(origin, prefix, errors);
        }
        Element geometry = firstChildElement(frame, "geometry");
        if (geometry == null) {
            errors.add(prefix + "缺少 <geometry>，碰撞/视觉几何引用不完整");
            return;
        }
        List<Element> geometryChildren = childElements(geometry, null);
        if (geometryChildren.size() != 1) {
            errors.add(prefix + "geometry 必须恰含一种几何形状");
            return;
        }
        Element shape = geometryChildren.get(0);
        if (!GEOMETRY_TYPES.contains(shape.getTagName())) {
            errors.add(prefix + "geometry 类型不支持: " + shape.getTagName());
            return;
        }
        switch (shape.getTagName()) {
            case "box" -> validateVectorAttribute(shape, "size", 3, prefix + "box ", errors);
            case "cylinder" -> {
                double radius = parseFiniteRequired(shape, "radius", prefix + "cylinder ", errors);
                double length = parseFiniteRequired(shape, "length", prefix + "cylinder ", errors);
                if (radius <= 0 || length <= 0) {
                    errors.add(prefix + "cylinder radius/length 必须为正");
                }
            }
            case "sphere" -> {
                double radius = parseFiniteRequired(shape, "radius", prefix + "sphere ", errors);
                if (radius <= 0) {
                    errors.add(prefix + "sphere radius 必须为正");
                }
            }
            case "mesh" -> {
                String filename = shape.getAttribute("filename");
                if (filename.isBlank()) {
                    errors.add(prefix + "mesh 必须声明非空 filename");
                }
            }
            default -> { }
        }
    }

    private void validateOrigin(Element origin, String prefix, List<String> errors) {
        if (origin.hasAttribute("xyz")) {
            validateVectorAttribute(origin, "xyz", 3, prefix + "origin ", errors);
        }
        if (origin.hasAttribute("rpy")) {
            validateVectorAttribute(origin, "rpy", 3, prefix + "origin ", errors);
        }
        // xyz 与 rpy 要么同时显式给出，要么均缺省（缺省按单位姿处理），保证坐标系描述完整。
        if (origin.hasAttribute("xyz") ^ origin.hasAttribute("rpy")) {
            errors.add(prefix + "origin 的 xyz 与 rpy 必须同时给出");
        }
    }

    private void validateVectorAttribute(Element element, String attribute, int dimensions,
                                         String prefix, List<String> errors) {
        String raw = element.getAttribute(attribute);
        if (raw.isBlank()) {
            errors.add(prefix + "缺少 " + attribute);
            return;
        }
        String[] parts = raw.trim().split("\\s+");
        if (parts.length != dimensions) {
            errors.add(prefix + attribute + " 需要 " + dimensions + " 个数值，实际: " + raw);
            return;
        }
        for (String part : parts) {
            Double value = parseFinite(part);
            if (value == null) {
                errors.add(prefix + attribute + " 含非有限数值: " + raw);
                return;
            }
        }
    }

    private double parseFiniteRequired(Element element, String attribute, String prefix, List<String> errors) {
        String raw = element.getAttribute(attribute);
        if (raw.isBlank()) {
            errors.add(prefix + "缺少 " + attribute);
            return Double.NaN;
        }
        Double value = parseFinite(raw);
        if (value == null) {
            errors.add(prefix + attribute + " 必须为有限数值，实际: " + raw);
            return Double.NaN;
        }
        return value;
    }

    private void parseFiniteOptional(Element element, String attribute, String prefix, List<String> errors) {
        if (!element.hasAttribute(attribute)) {
            return;
        }
        String raw = element.getAttribute(attribute);
        if (parseFinite(raw) == null) {
            errors.add(prefix + attribute + " 必须为有限数值，实际: " + raw);
        }
    }

    private Double parseFinite(String raw) {
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String requiredName(Element element, String tag, List<String> errors) {
        String name = element.getAttribute("name");
        if (name.isBlank()) {
            errors.add(tag + " 缺少非空 name 属性");
            return null;
        }
        return name;
    }

    private String describe(String jointName) {
        return jointName.isBlank() ? "joint(未命名) " : "joint " + jointName + " ";
    }

    private List<Element> childElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && (tagName == null || tagName.equals(node.getNodeName()))) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private Element firstChildElement(Element parent, String tagName) {
        List<Element> elements = childElements(parent, tagName);
        return elements.isEmpty() ? null : elements.get(0);
    }
}
