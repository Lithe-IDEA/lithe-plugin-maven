package io.github.litheidea.maven.backend;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Process-isolated regressions of the production JDK settings writer. */
public final class ConfigurationEditorRegression {
    public static void main(String[] args) throws Exception {
        String name = args[0];
        if ("preserve".equals(name)) {
            String original = "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\"><!--keep--><localRepository mode=\"retained\"/><servers><server><password>fixture-secret-sentinel</password></server></servers></settings>";
            byte[] bytes = original.getBytes(StandardCharsets.UTF_8), before = bytes.clone();
            String changed = replace(bytes, values("localRepository", "仓库 & value"));
            check(changed.contains("mode=\"retained\""), "attribute lost");
            check(changed.contains("<!--keep-->"), "comment lost");
            check(changed.contains("<password>fixture-secret-sentinel</password>"), "unrelated secret changed");
            check(changed.contains("仓库 &amp; value"), "text must be escaped");
            check(Arrays.equals(bytes, before), "input buffer changed");
        } else if ("namespace".equals(name)) {
            String changed = replace("<s:settings xmlns:s=\"http://maven.apache.org/SETTINGS/1.0.0\" xmlns:x=\"urn:other\"><x:localRepository>untouched</x:localRepository><s:localRepository>/before</s:localRepository></s:settings>".getBytes(StandardCharsets.UTF_8), values("localRepository", "/after"));
            check(changed.contains("<x:localRepository>untouched</x:localRepository>"), "foreign namespace changed");
            check(changed.contains("<s:localRepository>/after</s:localRepository>"), "namespace binding changed");
        } else if ("list".equals(name)) {
            String changed = replace("<settings><activeProfiles><!--keep-list--><activeProfile>old</activeProfile></activeProfiles><other>keep</other></settings>".getBytes(StandardCharsets.UTF_8), values("activeProfiles", Arrays.asList("ci", "Unicode-界 & value")));
            check(!changed.contains(">old<") && changed.contains("<activeProfile>ci</activeProfile>"), "list was not replaced");
            check(changed.contains("Unicode-界 &amp; value") && changed.contains("<!--keep-list-->"), "list text/comment lost");
            check(changed.contains("<other>keep</other>"), "unrelated node changed");
        } else if ("reset-and-missing".equals(name)) {
            String changed = replace("<settings><localRepository>/old</localRepository><activeProfiles><!--keep--><activeProfile>old</activeProfile></activeProfiles><other>keep</other></settings>".getBytes(StandardCharsets.UTF_8), values("activeProfiles", null));
            check(!changed.contains("activeProfiles") && changed.contains("<!--keep-->"), "reset did not preserve comment");
            check(changed.contains("<localRepository>/old</localRepository>"), "reset changed another field");
            changed = replace(null, values("localRepository", "/created"));
            check(changed.contains("http://maven.apache.org/SETTINGS/1.0.0") && changed.contains(">/created<"), "missing source creation failed");
            changed = replace("<settings/>".getBytes(StandardCharsets.UTF_8), values("activeProfiles", Arrays.asList()));
            check(changed.contains("activeProfiles"), "explicit empty list became absent");
        } else if ("bom".equals(name)) {
            String changed = replace("\ufeff<?xml version=\"1.0\" encoding=\"UTF-8\"?><settings><!--保留--></settings>".getBytes(StandardCharsets.UTF_8), values("localRepository", "仓库"));
            check(changed.startsWith("\ufeff") && changed.contains("<!--保留-->") && changed.contains("仓库"), "BOM/Unicode lost");
        } else if ("invalid".equals(name)) {
            for (String document : new String[] {
                "<!DOCTYPE settings [<!ENTITY x SYSTEM 'file:///private'>]><settings>&x;</settings>",
                "<settings><localRepository></settings>",
                "<settings><localRepository>a</localRepository><localRepository>b</localRepository></settings>",
                "<not-settings/>",
                "<settings><localRepository><unknown>must remain</unknown></localRepository></settings>"
            }) rejects(document);
            StringBuilder deep = new StringBuilder("<settings>");
            for (int i = 0; i < 65; i++) deep.append("<level>");
            for (int i = 0; i < 65; i++) deep.append("</level>");
            deep.append("</settings>"); rejects(deep.toString());
            boolean invalidCharacter = false;
            try { ConfigurationEditor.replace("<settings/>".getBytes(StandardCharsets.UTF_8), values("localRepository", "invalid" + (char) 1)); }
            catch (Exception expected) { invalidCharacter = true; }
            check(invalidCharacter, "invalid XML characters reached publication");
        } else throw new AssertionError("unknown regression case");
    }
    private static void rejects(String original) throws Exception {
        try { ConfigurationEditor.replace(original.getBytes(StandardCharsets.UTF_8), values("localRepository", "/new")); }
        catch (Exception expected) { return; }
        throw new AssertionError("unsafe settings were accepted");
    }
    private static String replace(byte[] original, Map<String, Object> values) throws Exception {
        return new String(ConfigurationEditor.replace(original, values), StandardCharsets.UTF_8);
    }
    private static Map<String, Object> values(String field, Object value) {
        Map<String, Object> values = new LinkedHashMap<>(); values.put(field, value); return values;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
