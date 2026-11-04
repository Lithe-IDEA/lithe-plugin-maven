package io.github.litheidea.plugin.sdk;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The host refused or failed a request this backend sent. {@link #code()} is
 * the stable {@code error.data.code}, such as {@code plugin.host.contextExpired};
 * {@link #rpcCode()} the JSON-RPC number; {@link #details()} the object a
 * version 3 host may add, such as {@code { option }} or
 * {@code { retryAfterMilliseconds }}.
 */
public final class HostError extends PluginSdkError {
    private static final long serialVersionUID = 1L;

    /**
     * Stable host failure codes of plugin-function protocol 3 and their
     * JSON-RPC numbers. A later host adds codes after {@code -32021}.
     */
    public static final Map<String, Integer> CODES;
    private static final Map<Integer, String> BY_RPC_CODE;

    static {
        Map<String, Integer> codes = new LinkedHashMap<>();
        codes.put("plugin.host.unavailable", -32601);
        codes.put("plugin.host.invalidParams", -32602);
        codes.put("plugin.host.failed", -32603);
        codes.put("plugin.host.cancelled", -32800);
        codes.put("plugin.host.permissionDenied", -32010);
        codes.put("plugin.host.contextExpired", -32011);
        codes.put("plugin.host.authorityUnsupported", -32012);
        codes.put("plugin.host.versionConflict", -32013);
        codes.put("plugin.host.limitExceeded", -32014);
        codes.put("plugin.host.timeout", -32015);
        codes.put("plugin.host.notFound", -32016);
        codes.put("plugin.host.readOnly", -32017);
        codes.put("plugin.host.unsupportedOption", -32018);
        codes.put("plugin.host.preconditionFailed", -32019);
        codes.put("plugin.host.busy", -32020);
        codes.put("plugin.host.rateLimited", -32021);
        CODES = Collections.unmodifiableMap(codes);
        Map<Integer, String> byNumber = new HashMap<>();
        for (Map.Entry<String, Integer> entry : codes.entrySet()) {
            byNumber.put(entry.getValue(), entry.getKey());
        }
        BY_RPC_CODE = Collections.unmodifiableMap(byNumber);
    }

    private final transient Map<String, Object> details;
    private final String method;

    public HostError(String code, String message, Integer rpcCode, Map<String, Object> details, String method) {
        super(code, message, rpcCode);
        this.details = details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        this.method = method;
    }

    /** The failure's {@code error.data.details}, or null when the host sent none. */
    public Map<String, Object> details() {
        return details;
    }

    /** The request that failed. */
    public String method() {
        return method;
    }

    /**
     * The failure for a JSON-RPC error answer. A host method without a known
     * code is a host failure: from version 3 a number names one code, and an
     * unknown number is {@code plugin.host.failed}.
     */
    static HostError fromResponse(String method, Map<String, Object> error) {
        Object data = error.get("data");
        Map<String, Object> fields = data instanceof Map ? Json.object(data) : Collections.<String, Object>emptyMap();
        Object rpcCode = error.get("code");
        Integer number = rpcCode instanceof Number && ((Number) rpcCode).doubleValue() == ((Number) rpcCode).intValue()
                ? ((Number) rpcCode).intValue()
                : null;
        String code = Json.string(fields, "code");
        if (code == null) {
            if (method.startsWith(Protocol.HOST_PREFIX)) {
                String known = number == null ? null : BY_RPC_CODE.get(number);
                code = known == null ? "plugin.host.failed" : known;
            } else {
                code = "sdk.host.error";
            }
        }
        Object details = fields.get("details");
        String text = Json.string(error, "message");
        return new HostError(code, text == null ? method + " failed." : text, number,
                details instanceof Map ? Json.object(details) : null, method);
    }
}
