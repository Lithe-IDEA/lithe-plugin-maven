package io.github.litheidea.maven.settings;

import io.github.litheidea.plugin.sdk.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/**
 * Native POM settings. Maven owns module discovery and effective compiler facts; this backend
 * projects an allowlist and prepares replacements without publishing.
 */
public final class MavenSettings {
  private static final int LIMIT = 2 * 1024 * 1024;
  private static final Map<String, Map<String, Binding>> BINDINGS = new ConcurrentHashMap<>();

  private static final class Binding {
    final Path path;
    final boolean release;

    Binding(Path path, boolean release) {
      this.path = path;
      this.release = release;
    }
  }

  private MavenSettings() {}

  public static void main(String[] args) {
    Backend backend = Backend.create("lithe-maven-settings", "development");
    backend.function("compiler.settings", (params, context) -> read(params, context, false));
    backend.function("libraries.settings", (params, context) -> read(params, context, true));
    backend.suppressSuccessDiagnostics("libraries.settings");
    backend.suppressSuccessDiagnostics("compiler.settings");
    backend.function("compiler.prepareSettings", MavenSettings::edit);
    backend.listen();
  }

  static Map<String, Object> object(Object value) {
    if (!(value instanceof Map))
      throw new PluginError("maven.invalidSettings", "Expected an object.");
    return (Map<String, Object>) value;
  }

  static List<?> list(Object value) {
    return value instanceof List ? (List<?>) value : Collections.emptyList();
  }

  static String text(Object value) {
    return value == null ? "" : value.toString();
  }

  static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
    return result;
  }

  static String local(boolean zh, String en, String cn) {
    return zh ? cn : en;
  }

  static Path root(CallContext context) throws Exception {
    if (context.workspaceContext() == null)
      throw new PluginError("maven.noWorkspace", "A workspace is required.");
    Object folders =
        context
            .host()
            .workspace()
            .getFolders(context.workspaceContext())
            .get(10, TimeUnit.SECONDS)
            .get("folders");
    if (list(folders).isEmpty())
      throw new PluginError("maven.noWorkspace", "A local workspace is required.");
    return Paths.get(text(object(list(folders).get(0)).get("path"))).toRealPath();
  }

  static byte[] bytes(Path path) throws Exception {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new byte[0];
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > LIMIT)
      throw new IOException("POM source is not a bounded regular file.");
    try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) {
        if (out.size() + n > LIMIT) throw new IOException("POM exceeds the source limit.");
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  static String digest(byte[] bytes) throws Exception {
    StringBuilder out = new StringBuilder("sha256:");
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
      out.append(String.format(Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }

  static Document parse(byte[] bytes) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    DocumentBuilder builder = f.newDocumentBuilder();
    builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
    return builder.parse(new ByteArrayInputStream(bytes));
  }

  static Element child(Element parent, String name) {
    if (parent == null) return null;
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element
          && name.equals(n.getLocalName() == null ? n.getNodeName() : n.getLocalName()))
        return (Element) n;
    return null;
  }

  static Element ensure(Element parent, String name) {
    Element value = child(parent, name);
    if (value == null) {
      value = parent.getOwnerDocument().createElementNS(parent.getNamespaceURI(), name);
      parent.appendChild(value);
    }
    return value;
  }

  static String value(Element parent, String name) {
    Element value = child(parent, name);
    return value == null ? null : value.getTextContent().trim();
  }

  static void set(Element parent, String name, String value) {
    Element current = child(parent, name);
    if (value == null) {
      if (current != null) parent.removeChild(current);
    } else ensure(parent, name).setTextContent(value);
  }

  static Element compiler(Document doc, boolean create) {
    Element project = doc.getDocumentElement(),
        build = child(project, "build"),
        plugins = child(build, "plugins");
    if (plugins != null)
      for (Node n = plugins.getFirstChild(); n != null; n = n.getNextSibling())
        if (n instanceof Element
            && "maven-compiler-plugin".equals(value((Element) n, "artifactId"))
            && (value((Element) n, "groupId") == null
                || "org.apache.maven.plugins".equals(value((Element) n, "groupId"))))
          return (Element) n;
    if (!create) return null;
    plugins = ensure(ensure(project, "build"), "plugins");
    Element plugin = doc.createElementNS(project.getNamespaceURI(), "plugin");
    plugins.appendChild(plugin);
    set(plugin, "groupId", "org.apache.maven.plugins");
    set(plugin, "artifactId", "maven-compiler-plugin");
    set(plugin, "version", "3.13.0");
    return plugin;
  }

  static Object nativeTarget(Document doc) {
    Element config = child(compiler(doc, false), "configuration");
    String release = value(config, "release"), target = value(config, "target");
    if (release != null && !release.isEmpty()) return normalize(release);
    if (target != null) return normalize(target);
    Element props = child(doc.getDocumentElement(), "properties");
    release = value(props, "maven.compiler.release");
    target = value(props, "maven.compiler.target");
    return release != null ? normalize(release) : target == null ? null : normalize(target);
  }

  static String normalize(String value) {
    return value != null && value.startsWith("1.") ? value.substring(2) : value;
  }

  static Map<String, Object> field(
      boolean zh,
      String id,
      String en,
      String cn,
      String kind,
      Object raw,
      Object effective,
      String origin,
      List<Object> options,
      String help) {
    if ("enum".equals(kind)) {
      choice(options, raw);
      choice(options, effective);
    }
    return map(
        "id",
        id,
        "title",
        local(zh, en, cn),
        "description",
        help,
        "kind",
        kind,
        "value",
        raw,
        "effectiveValue",
        effective,
        "effectiveSource",
        origin,
        "options",
        options);
  }

  static Object read(Map<String, Object> params, CallContext context, boolean libraryView)
      throws Exception {
    Path root = root(context);
    Map<String, Object> environment = object(params.get("context"));
    boolean zh = text(environment.get("language")).startsWith("zh");
    List<Object> sources = new ArrayList<>();
    Map<String, Binding> bindings = new LinkedHashMap<>();
    Set<Path> seen = new HashSet<>();
    long total = 0;
    for (Object build : list(environment.get("builds"))) {
      Map<String, Object> b = object(build), model = object(b.get("model"));
      for (Object projectValue : list(model.get("projects"))) {
        context.throwIfCancelled();
        Map<String, Object> project = object(projectValue);
        String relative = text(project.get("path"));
        Path path = root.resolve(relative).resolve("pom.xml").normalize();
        if (!path.startsWith(root)
            || !Files.isRegularFile(path)
            || !path.toRealPath().startsWith(root))
          throw new IOException("Maven returned a project outside this workspace.");
        if (!seen.add(path)) continue;
        if (seen.size() > 1024) throw new IOException("Too many Maven modules.");
        byte[] raw = bytes(path);
        total += raw.length;
        if (total > 8L * 1024 * 1024)
          throw new IOException("Project settings exceed the total read limit.");
        Document doc = parse(raw);
        String id =
            "pom"
                + digest(root.relativize(path).toString().getBytes(StandardCharsets.UTF_8))
                    .substring(7);
        Map<String, Object> properties = object(project.get("properties"));
        bindings.put(
            id,
            new Binding(
                path, Boolean.parseBoolean(text(properties.get("compiler.releaseEnabled")))));
        Element config = child(compiler(doc, false), "configuration");
        List<Object> fields = new ArrayList<>(), choices = new ArrayList<>();
        for (int target = 1; target <= 26; target++)
          choices.add(map("value", Integer.toString(target), "title", Integer.toString(target)));
        String origin = local(zh, "Maven effective project", "Maven 生效项目配置");
        fields.add(
            field(
                zh,
                "target",
                "Target bytecode",
                "目标字节码",
                "enum",
                nativeTarget(doc),
                nullable(normalize(text(properties.get("compiler.target")))),
                origin,
                choices,
                local(
                    zh,
                    "Writes the native compiler configuration. The selected compiler JDK must"
                        + " support this target. Module overrides remain in their own POM.",
                    "写入原生编译器配置；所选编译 JDK 必须支持此目标。模块覆盖保存在各自 POM 中。")));
        String release = value(config, "release");
        fields.add(
            field(
                zh,
                "release",
                "Use --release",
                "使用 --release",
                "boolean",
                release == null ? null : !release.isEmpty(),
                Boolean.parseBoolean(text(properties.get("compiler.releaseEnabled"))),
                origin,
                Collections.emptyList(),
                local(
                    zh,
                    "Requires Maven Compiler Plugin 3.6 or newer. When disabled, source and target"
                        + " are set together.",
                    "需要 Maven Compiler Plugin 3.6 或更高版本；关闭后同时设置 source 与 target。")));
        for (String[] f :
            new String[][] {
              {"debug", "Generate debugging information", "生成调试信息"},
              {"deprecation", "Report deprecated features", "报告已弃用的功能"},
              {"warnings", "Report warnings", "报告警告"}
            }) {
          String v = value(config, nativeName(f[0]));
          fields.add(
              field(
                  zh,
                  f[0],
                  f[1],
                  f[2],
                  "boolean",
                  v == null ? null : Boolean.valueOf(v),
                  nullableBoolean(properties.get("compiler." + nativeName(f[0]))),
                  origin,
                  Collections.emptyList(),
                  ""));
        }
        List<String> args = new ArrayList<>();
        Element argsElement = child(config, "compilerArgs");
        if (argsElement != null)
          for (Node n = argsElement.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element) args.add(n.getTextContent());
        fields.add(
            field(
                zh,
                "arguments",
                "Additional compiler arguments",
                "附加编译参数",
                "stringList",
                argsElement == null ? null : args,
                properties.containsKey("compiler.arguments")
                    ? Json.parse(text(properties.get("compiler.arguments")))
                    : null,
                origin,
                Collections.emptyList(),
                local(
                    zh,
                    "One compiler argument per line. Project-specific arguments remain in the POM.",
                    "每行一个编译参数；项目参数保存在 POM 中。")));
        List<String> libs = libraries(doc);
        fields.add(
            field(
                zh,
                "libraries",
                "Local JAR files",
                "本地 JAR 文件",
                "fileList",
                libs,
                libs,
                origin,
                Collections.emptyList(),
                local(
                    zh,
                    "Adds native system-scope dependencies. Prefer repository dependencies for"
                        + " portable builds; existing dependencies are preserved.",
                    "添加原生 system 作用域依赖。可移植构建优先使用仓库依赖；保留已有其他依赖。")));
        String nativePath = root.relativize(path).toString().replace(File.separatorChar, '/');
        boolean projectDefault =
            relative.equals(".") || relative.isEmpty() || relative.equals(text(b.get("root")));
        for (Object fieldValue : fields) {
          Map<String, Object> f = object(fieldValue);
          String fieldID = text(f.get("id"));
          Object inherited =
              properties.get(
                  "compiler.inherited."
                      + (fieldID.equals("release") ? "releaseEnabled" : nativeName(fieldID)));
          if (inherited != null) {
            if ("boolean".equals(f.get("kind"))) inherited = Boolean.valueOf(inherited.toString());
            if ("stringList".equals(f.get("kind"))) inherited = Json.parse(inherited.toString());
            if ("target".equals(fieldID)) inherited = normalize(inherited.toString());
            f.put("inheritedValue", inherited);
            if ("enum".equals(f.get("kind"))) choice((List<Object>) f.get("options"), inherited);
          }
        }
        if (libraryView) fields.removeIf(f -> !"libraries".equals(object(f).get("id")));
        else
          fields.removeIf(
              f ->
                  "libraries".equals(object(f).get("id"))
                      || (!projectDefault && !"target".equals(object(f).get("id"))));
        sources.add(
            map(
                "id",
                id,
                "group",
                libraryView ? "libraries" : projectDefault ? "project" : "modules",
                "title",
                project.get("name"),
                "path",
                nativePath,
                "revision",
                digest(raw),
                "writable",
                true,
                "fields",
                fields));
      }
    }
    if (sources.isEmpty())
      throw new PluginError("maven.noProject", "No Maven project is available.");
    synchronized (BINDINGS) {
      if (BINDINGS.size() >= 32 && !BINDINGS.containsKey(root.toString()))
        throw new IOException("Too many workspace settings sessions.");
      BINDINGS.put(root.toString(), bindings);
    }
    return map(
        "format",
        "litheSettingsSources",
        "version",
        0,
        "title",
        (libraryView
            ? local(zh, "Maven Libraries", "Maven 库")
            : local(zh, "Maven Java Compiler", "Maven Java 编译器")),
        "groups",
        libraryView
            ? Collections.singletonList(
                map(
                    "id",
                    "libraries",
                    "title",
                    local(zh, "Local JAR dependencies", "本地 JAR 依赖"),
                    "layout",
                    "form"))
            : Arrays.asList(
                map(
                    "id",
                    "project",
                    "title",
                    local(zh, "Project defaults", "项目默认设置"),
                    "layout",
                    "form"),
                map(
                    "id",
                    "modules",
                    "title",
                    local(zh, "Per-module bytecode", "按模块设置字节码"),
                    "rowTitle",
                    local(zh, "Module", "模块"),
                    "layout",
                    "table")),
        "sources",
        sources);
  }

  static void choice(List<Object> options, Object value) {
    if (value != null
        && !value.toString().isEmpty()
        && options.stream().noneMatch(option -> value.equals(object(option).get("value"))))
      options.add(map("value", value, "title", value.toString()));
  }

  static String nativeName(String id) {
    return id.equals("deprecation")
        ? "showDeprecation"
        : id.equals("warnings") ? "showWarnings" : id;
  }

  static Object nullable(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  static Object nullableBoolean(Object value) {
    return value == null ? null : Boolean.valueOf(value.toString());
  }

  static List<String> libraries(Document doc) {
    List<String> result = new ArrayList<>();
    Element deps = child(doc.getDocumentElement(), "dependencies");
    if (deps != null)
      for (Node n = deps.getFirstChild(); n != null; n = n.getNextSibling())
        if (n instanceof Element
            && "local.lithe.jar".equals(value((Element) n, "groupId"))
            && "system".equals(value((Element) n, "scope")))
          result.add(value((Element) n, "systemPath"));
    return result;
  }

  static Object edit(Map<String, Object> params, CallContext context) throws Exception {
    Path root = root(context);
    String id = text(params.get("sourceID"));
    Map<String, Binding> bindings = BINDINGS.get(root.toString());
    Binding binding = bindings == null ? null : bindings.get(id);
    Path path = binding == null ? null : binding.path;
    if (path == null || !path.toRealPath().startsWith(root))
      throw new IOException("Reload the native settings before saving.");
    byte[] original = bytes(path);
    if (!digest(original).equals(params.get("revision")))
      throw new IOException("The POM changed outside this editor.");
    Map<String, Object> values = object(params.get("values"));
    Document doc = parse(original);
    Element config = ensure(compiler(doc, true), "configuration");
    Object targetValue = values.containsKey("target") ? values.get("target") : nativeTarget(doc);
    String target = targetValue == null ? null : text(targetValue);
    boolean useRelease =
        values.containsKey("release")
            ? Boolean.TRUE.equals(values.get("release"))
            : binding.release;
    if (values.containsKey("target") || values.containsKey("release")) {
      if (target != null
          && !target.equals(nativeTarget(doc))
          && !target.matches("(?:[1-9]|1[0-9]|2[0-6])"))
        throw new IOException("Choose a supported Java target.");
      set(config, "release", useRelease ? target : target == null ? null : "");
      if (!useRelease && target != null)
        ensure(config, "release").setAttribute("combine.self", "override");
      set(config, "source", useRelease ? null : target);
      set(config, "target", useRelease ? null : target);
      if (target == null) {
        Element props = child(doc.getDocumentElement(), "properties");
        if (props != null)
          for (String key :
              new String[] {
                "maven.compiler.source", "maven.compiler.target", "maven.compiler.release"
              }) set(props, key, null);
      }
    }
    for (Map.Entry<String, Object> edit : values.entrySet()) {
      context.throwIfCancelled();
      String key = edit.getKey();
      Object v = edit.getValue();
      if (Arrays.asList("target", "release").contains(key)) continue;
      if (Arrays.asList("debug", "deprecation", "warnings").contains(key))
        set(config, nativeName(key), v == null ? null : v.toString());
      else if (key.equals("arguments")) {
        Element old = child(config, "compilerArgs");
        if (old != null) config.removeChild(old);
        if (v != null) {
          Element args = ensure(config, "compilerArgs");
          for (Object arg : list(v)) {
            Element a = doc.createElementNS(config.getNamespaceURI(), "arg");
            a.setTextContent(text(arg));
            args.appendChild(a);
          }
        }
      } else if (key.equals("libraries")) {
        Element deps = ensure(doc.getDocumentElement(), "dependencies");
        for (Node n = deps.getFirstChild(); n != null; ) {
          Node next = n.getNextSibling();
          if (n instanceof Element
              && "local.lithe.jar".equals(value((Element) n, "groupId"))
              && "system".equals(value((Element) n, "scope"))) deps.removeChild(n);
          n = next;
        }
        for (Object jar : list(v)) {
          String location = text(jar);
          Path input = Paths.get(location);
          if (!input.isAbsolute()) input = path.getParent().resolve(input);
          if (!Files.isRegularFile(input) || !location.toLowerCase(Locale.ROOT).endsWith(".jar"))
            throw new IOException("Choose an existing JAR file.");
          Element dep = doc.createElementNS(deps.getNamespaceURI(), "dependency");
          deps.appendChild(dep);
          set(dep, "groupId", "local.lithe.jar");
          set(
              dep,
              "artifactId",
              "jar-" + digest(location.getBytes(StandardCharsets.UTF_8)).substring(7, 23));
          set(dep, "version", "1");
          set(dep, "scope", "system");
          set(dep, "systemPath", input.toAbsolutePath().normalize().toString());
        }
      } else throw new IOException("Unknown native setting.");
    }
    context.throwIfCancelled();
    TransformerFactory factory = TransformerFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    Transformer transformer = factory.newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    transformer.transform(new DOMSource(doc), new StreamResult(out));
    byte[] replacement = out.toByteArray();
    if (replacement.length > LIMIT) throw new IOException("POM exceeds the source limit.");
    Path stage = Paths.get(text(params.get("outputFile")));
    if (!Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("The staging file no longer exists.");
    context.throwIfCancelled();
    try (OutputStream stream =
        Files.newOutputStream(
            stage,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS)) {
      stream.write(replacement);
    }
    return map(
        "format",
        "litheSettingsSourceEdit",
        "version",
        0,
        "sourceID",
        id,
        "revision",
        digest(replacement),
        "changed",
        !Arrays.equals(original, replacement));
  }
}
