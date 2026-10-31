package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Maps JDT's public fullName identity to Surefire, without reading Java sources. */
final class MavenTestSelection {
    private final Map<String, String[]> selections = new LinkedHashMap<>();

    static MavenTestSelection from(Map<String, Object> params) {
        MavenTestSelection result = new MavenTestSelection();
        Object kind = params.get("selectorKind"), values = params.get("selectors");
        if (kind == null && values == null) {
            Object suite = params.get("suite"), method = params.get("case");
            if (suite == null) {
                if (method != null) throw invalid("A Maven test method needs its suite.");
                return result;
            }
            if (!(suite instanceof String) || !exactName((String) suite, false)
                    || method != null && (!(method instanceof String) || !exactName((String) method, true))) {
                throw invalid("The test selector cannot be represented as an exact Surefire class or method.");
            }
            String key = suite + (method == null ? "" : "#" + method);
            result.selections.put(key, method == null ? new String[] {(String) suite} : new String[] {(String) suite, (String) method});
            return result;
        }
        if (!"java.test.fullName".equals(kind) || !(values instanceof List)
                || ((List<?>) values).size() > 64 || params.containsKey("suite") || params.containsKey("case")) {
            throw invalid("Unsupported or conflicting semantic test selectors.");
        }
        for (Object value : (List<?>) values) {
            if (!(value instanceof String)) throw invalid("A test selector must be a string.");
            String identifier = (String) value;
            // Surefire can address a method by name, but cannot distinguish
            // overloaded signatures. Reject those rather than silently widening.
            if (identifier.endsWith("()")) identifier = identifier.substring(0, identifier.length() - 2);
            String[] parts = identifier.split("#", -1);
            if (parts.length > 2 || !exactName(parts[0], false)
                    || parts.length == 2 && !exactName(parts[1], true)) {
                throw invalid("The semantic test selector cannot be represented as an exact Surefire class or method.");
            }
            result.selections.put(identifier, parts);
        }
        return result;
    }

    static boolean exactName(String value, boolean method) {
        return value.matches(method ? "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*"
                : "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*");
    }

    /** Surefire's parameterized invocations retain one Java method identity. */
    static String reportMethod(String name) {
        int paren = name.indexOf('('), bracket = name.indexOf('[');
        int end = paren < 0 ? bracket : bracket < 0 ? paren : Math.min(paren, bracket);
        if (end <= 0) return name;
        String method = name.substring(0, end);
        return exactName(method, true) ? method : name;
    }

    boolean isFiltered() { return !selections.isEmpty(); }

    String argument() {
        List<String> filters = new java.util.ArrayList<>();
        for (String[] selected : selections.values()) {
            // Literal/FQN patterns receive **/ in Surefire's ResolvedTest.
            // Its public %regex[class#method] form retains the complete class
            // path. Parameter suffixes cannot name another Java method.
            String filter = Pattern.quote(selected[0].replace('.', java.io.File.separatorChar) + ".class");
            if (selected.length == 2) filter += "#" + Pattern.quote(selected[1]) + "(?:\\(.*\\))?(?:\\[.*\\])?";
            filters.add("%regex[" + filter + "]");
        }
        return "-Dtest=" + String.join(",", filters);
    }

    /** A dependency's same-named test cannot satisfy the selected project's request. */
    void verify(Map<String, Object> report, String project) {
        Set<String> missing = new LinkedHashSet<>(selections.keySet());
        Object values = report.get("tests");
        if (values instanceof List) for (Object value : (List<?>) values) {
            Map<String, Object> test = Json.object(value);
            if (project != null && !project.equals(test.get("project"))) continue;
            for (Map.Entry<String, String[]> selected : selections.entrySet()) {
                String[] parts = selected.getValue();
                if (parts[0].equals(test.get("suite")) && (parts.length == 1 || parts[1].equals(test.get("case")))) {
                    missing.remove(selected.getKey());
                }
            }
        }
        if (!missing.isEmpty()) throw new PluginError("maven.noMatchingTests",
                "Surefire produced no fresh result in the selected project for one or more semantic test selectors.");
    }

    /** Complete suites replace their old methods; a method run cannot clear siblings. */
    void addCoverage(Map<String, Object> report) {
        Map<String, Object> covered = new LinkedHashMap<>();
        Object values = report.get("tests");
        if (values instanceof List) for (Object value : (List<?>) values) {
            Map<String, Object> test = Json.object(value);
            String project = Json.string(test, "project"), suite = Json.string(test, "suite"), method = Json.string(test, "case");
            // An unfiltered UI request can still inherit upstream -Dtest settings.
            // Only an explicit typed class selection proves suite coverage.
            boolean wholeSuite = false;
            for (String[] selected : selections.values()) {
                if (selected.length == 1 && selected[0].equals(suite)) wholeSuite = true;
            }
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("project", project); scope.put("suite", suite);
            if (!wholeSuite) scope.put("case", method);
            covered.put(Json.stringify(scope), scope);
        }
        report.put("coverage", new java.util.ArrayList<>(covered.values()));
    }

    private static PluginError invalid(String message) { return new PluginError("maven.invalidSelection", message); }
}
