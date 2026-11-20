package jp.try0.servlet.ipblock;

import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** テスト用の最小限の Servlet API 実装（動的プロキシ）。 */
final class ServletFakes {

    private ServletFakes() {
    }

    static HttpServletRequest request(String remoteAddr) {
        return request(remoteAddr, Map.of());
    }

    /** ヘッダー付きのリクエスト。ヘッダー名の大文字・小文字は区別しない（実際のコンテナと同じ）。 */
    static HttpServletRequest request(String remoteAddr, Map<String, List<String>> headers) {
        return proxy(HttpServletRequest.class, (method, args) -> {
            switch (method.getName()) {
                case "getRemoteAddr":
                    return remoteAddr;
                case "getHeaders":
                    return Collections.enumeration(headerValues(headers, (String) args[0]));
                case "getHeader": {
                    List<String> values = headerValues(headers, (String) args[0]);
                    return values.isEmpty() ? null : values.get(0);
                }
                default:
                    return null;
            }
        });
    }

    private static List<String> headerValues(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream())
                .collect(Collectors.toList());
    }

    static FilterConfig filterConfig(Map<String, String> params) {
        ServletContext context = proxy(ServletContext.class, (method, args) -> null);
        return new FilterConfig() {
            @Override
            public String getFilterName() {
                return "ipBlockFilter";
            }

            @Override
            public ServletContext getServletContext() {
                return context;
            }

            @Override
            public String getInitParameter(String name) {
                return params.get(name);
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.enumeration(params.keySet());
            }
        };
    }

    /** sendError されたステータスを記録するレスポンス。 */
    static final class RecordingResponse {
        Integer errorStatus;
        final HttpServletResponse proxy = proxy(HttpServletResponse.class, (method, args) -> {
            if ("sendError".equals(method.getName())) {
                errorStatus = (Integer) args[0];
            }
            return null;
        });
    }

    private interface Handler {
        Object handle(Method method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, method, args) -> {
            Object result = handler.handle(method, args);
            Class<?> rt = method.getReturnType();
            if (result == null && rt.isPrimitive() && rt != void.class) {
                return rt == boolean.class ? Boolean.FALSE
                        : rt == long.class ? Long.valueOf(0L)
                        : rt == int.class ? Integer.valueOf(0)
                        : rt == double.class ? Double.valueOf(0d)
                        : rt == float.class ? Float.valueOf(0f)
                        : rt == short.class ? Short.valueOf((short) 0)
                        : rt == byte.class ? Byte.valueOf((byte) 0)
                        : Character.valueOf('\0');
            }
            return result;
        });
    }
}
