package com.ecat.integration.agentbridge.tool;

import com.ecat.integration.EcatCoreApiIntegration.Auth.AuthManager;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MediaUrlSigner} 单元测试。
 *
 * <p>验证：把 ecat-media:// URI 经 AuthManager.signMediaUrl 得 host-less 签名路径后，
 * 用请求 Host（缺失回退 baseUrl host:port）+ baseUrl scheme 拼成完整无 token 下载 URL。
 * scheme 取自 baseUrl（跟随部署 http/https），host:port 取自请求 Host（支持远程 agent）。
 *
 * @author coffee
 */
public class MediaUrlSignerTest {

    private static final String SIGNED_PATH = "/core-api/media/stream?uri=x&exp=1&sig=y";
    private static final String URI =
            "ecat-media://com.ecat:integration-media-test-client/snapshots/test.jpg";

    /** 构造一个 signMediaUrl(URI)→signedPath 的 stub AuthManager。 */
    private AuthManager newAuthManagerReturning(String signedPath) {
        AuthManager am = mock(AuthManager.class);
        when(am.signMediaUrl(URI)).thenReturn(signedPath);
        return am;
    }

    @Test
    public void constructor_nullAuthManager_throws() {
        try {
            new MediaUrlSigner(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void sign_normal_buildsFullDownloadUrlWithRequestHost() {
        MediaUrlSigner signer = new MediaUrlSigner(newAuthManagerReturning(SIGNED_PATH));
        Map<String, Object> r = signer.sign(URI, "127.0.0.1:9999", "http://127.0.0.1:9999");
        assertEquals(URI, r.get("uri"));
        assertEquals("http://127.0.0.1:9999" + SIGNED_PATH, r.get("downloadUrl"));
        assertEquals(300, r.get("expiresInSeconds"));
    }

    @Test
    public void sign_requestHostNull_fallsBackToBaseUrlHostPort() {
        MediaUrlSigner signer = new MediaUrlSigner(newAuthManagerReturning(SIGNED_PATH));
        Map<String, Object> r = signer.sign(URI, null, "http://192.168.1.5:9999");
        assertEquals("http://192.168.1.5:9999" + SIGNED_PATH, r.get("downloadUrl"));
    }

    @Test
    public void sign_requestHostBlank_fallsBackToBaseUrlHostPort() {
        MediaUrlSigner signer = new MediaUrlSigner(newAuthManagerReturning(SIGNED_PATH));
        Map<String, Object> r = signer.sign(URI, "  ", "http://192.168.1.5:9999");
        assertEquals("http://192.168.1.5:9999" + SIGNED_PATH, r.get("downloadUrl"));
    }

    @Test
    public void sign_requestHostOverridesBaseUrlHostPort() {
        // 远程 agent 连 core 的外部地址：scheme 仍取 baseUrl（http），host:port 取请求 Host
        MediaUrlSigner signer = new MediaUrlSigner(newAuthManagerReturning(SIGNED_PATH));
        Map<String, Object> r = signer.sign(URI, "core.example.com:8080", "http://127.0.0.1:9999");
        assertEquals("http://core.example.com:8080" + SIGNED_PATH, r.get("downloadUrl"));
    }

    @Test
    public void sign_httpsBaseUrl_usesHttpsScheme() {
        MediaUrlSigner signer = new MediaUrlSigner(newAuthManagerReturning(SIGNED_PATH));
        Map<String, Object> r = signer.sign(URI, null, "https://core.example.com");
        assertTrue("downloadUrl should start with https://",
                ((String) r.get("downloadUrl")).startsWith("https://core.example.com/core-api/"));
    }

    @Test
    public void sign_signMediaUrlReturnsNull_throwsIllegalStateException() {
        // signMediaUrl 返回 null（mediaSecretKey 未就绪或 uri 无效）→ 严格模式不兜底
        AuthManager am = mock(AuthManager.class);
        when(am.signMediaUrl(URI)).thenReturn(null);
        MediaUrlSigner signer = new MediaUrlSigner(am);
        try {
            signer.sign(URI, "127.0.0.1:9999", "http://127.0.0.1:9999");
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void sign_nullUri_throwsIllegalArgument() {
        MediaUrlSigner signer = new MediaUrlSigner(newAuthManagerReturning(SIGNED_PATH));
        try {
            signer.sign(null, "127.0.0.1:9999", "http://127.0.0.1:9999");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void sign_emptyUri_throwsIllegalArgument() {
        MediaUrlSigner signer = new MediaUrlSigner(newAuthManagerReturning(SIGNED_PATH));
        try {
            signer.sign("", "127.0.0.1:9999", "http://127.0.0.1:9999");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
