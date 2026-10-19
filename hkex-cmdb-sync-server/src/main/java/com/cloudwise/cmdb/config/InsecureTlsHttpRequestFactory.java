package com.cloudwise.cmdb.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * 不校验 HTTPS 证书的请求工厂，仅在 {@code servicenow.allow-insecure-tls=true} 时使用。
 *
 * <p>为什么会存在这个类：现场经公司代理访问 ServiceNow 时，代理会用自己的证书重签流量，
 * 而该证书链不在 JDK 的信任库里，表现为 {@code PKIX path building failed}。
 * 正规做法是把公司根证书导进 JDK 的信任库；本类提供的是**临时绕过**手段，
 * 供现场拿不到证书、需要先把链路跑通时使用。</p>
 *
 * <p><b>开启后不再校验对端证书，也不再校验主机名</b>，因此 ServiceNow 的集成账号密码和
 * Token 对链路上任何能应答该地址的一方都是可见的。交付默认关闭，开启时启动自检会打 WARN。</p>
 *
 * <p>作用范围仅限挂了这个工厂的客户端（见 {@link HttpClientConfig}），
 * 不修改 JVM 全局的 {@code SSLContext}、默认信任库或默认主机名校验器。</p>
 *
 * @author hkex-cmdb-sync
 */
class InsecureTlsHttpRequestFactory extends SimpleClientHttpRequestFactory {

    /** 接受任何证书，不做链校验，也不因为证书过期或域名不符而拒绝。 */
    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // 只做客户端，不校验对端。
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // 刻意留空：这就是"忽略证书"的实现点。
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private final SSLSocketFactory sslSocketFactory;

    InsecureTlsHttpRequestFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {TRUST_ALL}, new SecureRandom());
            this.sslSocketFactory = context.getSocketFactory();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to build the insecure TLS context", exception);
        }
    }

    /**
     * 先交给父类做常规准备（请求方法、超时、输入输出），再覆盖 HTTPS 的证书与主机名校验。
     *
     * <p>顺序不能颠倒：父类会按 HTTP 方法设置 {@code doOutput} 等属性，先覆盖 TLS 不影响它，
     * 但反过来会让父类的准备逻辑作用在一个已经改过 TLS 的连接上，语义不清晰。</p>
     */
    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        if (connection instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(sslSocketFactory);
            // 主机名校验与证书校验是两件事，只换 TrustManager 仍会因域名不符失败，这里一并关闭。
            https.setHostnameVerifier((hostname, session) -> true);
        }
    }
}
