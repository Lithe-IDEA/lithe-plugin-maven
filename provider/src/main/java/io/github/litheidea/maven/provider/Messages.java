package io.github.litheidea.maven.provider;

import java.util.List;

/**
 * The provider's user-facing text in the interface language Lithe sends as
 * {@code locale} in {@code initialize}: Chinese for {@code zh*}, English
 * otherwise. Lithe shows a failed preparation's message as it is.
 */
final class Messages {
    private final boolean chinese;

    Messages(String locale) {
        chinese = locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
    }

    String compiling(String target) {
        return chinese ? "正在编译 " + target + "…" : "Compiling " + target + "…";
    }

    String buildFailed(int exitCode) {
        return chinese
                ? "Maven 编译失败（退出码 " + exitCode + "），详见运行窗口中的 Maven 输出。"
                : "The Maven build failed (exit code " + exitCode + "). See the Maven output in the Run window.";
    }

    String noRunDocument() {
        return chinese
                ? "Maven 没有写出运行信息，Lithe 的 Maven 扩展可能没有加载。"
                : "Maven wrote no run information; Lithe's Maven extension may not have loaded.";
    }

    String noMainClass(String target) {
        return chinese
                ? target + " 编译后没有带 main 方法的类。请在运行配置的“入口”里填写主类。"
                : target + " has no class with a main method after compiling. Enter the main class as the run configuration's entry point.";
    }

    String ambiguousMainClass(String target, List<String> candidates) {
        String list = String.join(", ", candidates.subList(0, Math.min(candidates.size(), 10)));
        return chinese
                ? target + " 有多个带 main 方法的类（" + list + "）。请在运行配置的“入口”里选定一个。"
                : target + " has several classes with a main method (" + list + "). Enter one as the run configuration's entry point.";
    }
}
