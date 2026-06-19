package com.ecat.integration.agentbridge.tool;

import com.ecat.integration.EcatCoreApiIntegration.Auth.AuthManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 ecat-media:// URI 转换为 agent 可直接 GET 的完整无 token 下载 URL。
 *
 * <p>Plan A 下载机制：复用 web UI 同款签名算法（{@link AuthManager#signMediaUrl(String)}，
 * HMAC-SHA256 + 5 分钟 TTL，落点 {@code /core-api/media/stream}），在 agent-bridge 进程内
 * <b>强类型直调</b>（非反射）得到 host-less 的签名路径，再用「请求 Host + baseUrl scheme」拼成
 * 完整 URL 返回给 agent。agent 拿到 downloadUrl 后无需任何 token，直接 GET 即可拉取二进制
 * （快照 JPEG / 录像 MP4）。
 *
 * <p>host 来源：优先取 MCP 请求的 Host header（支持远程 agent 连到 core 的外部地址），
 * 缺失时回退 baseUrl 的 host:port。scheme 取自 baseUrl（跟随部署 http/https）。
 *
 * <p>严格模式：uri 为空、authManager 为 null、或 signMediaUrl 返回 null（mediaSecretKey 未就绪 /
 * uri 非法）一律抛异常，不写兜底 URL。
 *
 * @author coffee
 */
public class MediaUrlSigner {

    /** 下载 URL 有效期（秒），与 AuthManager.MEDIA_URL_TTL_SECONDS 一致（5 分钟）。 */
    static final int EXPIRES_IN_SECONDS = 300;

    private final AuthManager authManager;

    public MediaUrlSigner(AuthManager authManager) {
        if (authManager == null) {
            throw new IllegalArgumentException("authManager must not be null");
        }
        this.authManager = authManager;
    }

    /**
     * 为给定 uri 生成完整无 token 下载 URL。
     *
     * @param uri             ecat-media:// URI（非空）
     * @param requestHostPort MCP 请求 Host header 的 host:port（缺失/空白则回退 baseUrl）
     * @param baseUrl         agent-bridge 自调用 baseUrl，用于取 scheme（http/https）与 host:port 回退
     * @return 含 {@code uri} / {@code downloadUrl} / {@code expiresInSeconds} 的有序 map
     * @throws IllegalArgumentException uri 为空
     * @throws IllegalStateException    signMediaUrl 返回 null 或 baseUrl 缺 scheme
     */
    public Map<String, Object> sign(String uri, String requestHostPort, String baseUrl) {
        if (uri == null || uri.isEmpty()) {
            throw new IllegalArgumentException("uri must not be null/empty");
        }
        String signedPath = authManager.signMediaUrl(uri);
        if (signedPath == null) {
            throw new IllegalStateException(
                    "signMediaUrl returned null for uri (mediaSecretKey not ready or invalid uri): " + uri);
        }
        String scheme = extractScheme(baseUrl);
        String hostPort = (requestHostPort != null && !requestHostPort.trim().isEmpty())
                ? requestHostPort.trim()
                : extractHostPort(baseUrl);
        String downloadUrl = scheme + hostPort + signedPath;

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("uri", uri);
        result.put("downloadUrl", downloadUrl);
        result.put("expiresInSeconds", EXPIRES_IN_SECONDS);
        return result;
    }

    /** 从 baseUrl 取 scheme（含 {@code ://}），如 {@code http://}。baseUrl 缺 {@code ://} 抛异常。 */
    private static String extractScheme(String baseUrl) {
        int idx = baseUrl == null ? -1 : baseUrl.indexOf("://");
        if (idx < 0) {
            throw new IllegalStateException("baseUrl 缺 scheme ('://' not found): " + baseUrl);
        }
        return baseUrl.substring(0, idx) + "://";
    }

    /** 从 baseUrl 取 host:port（去掉 scheme 与首个 {@code /} 之后的 path）。 */
    private static String extractHostPort(String baseUrl) {
        String rest = baseUrl.substring(baseUrl.indexOf("://") + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
