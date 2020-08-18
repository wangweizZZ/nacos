/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.common.http.client.request;

import com.alibaba.nacos.common.constant.HttpHeaderConsts;
import com.alibaba.nacos.common.http.HttpClientConfig;
import com.alibaba.nacos.common.http.HttpUtils;
import com.alibaba.nacos.common.http.client.response.HttpClientResponse;
import com.alibaba.nacos.common.http.client.response.JdkHttpClientResponse;
import com.alibaba.nacos.common.http.param.Header;
import com.alibaba.nacos.common.http.param.MediaType;
import com.alibaba.nacos.common.model.RequestHttpEntity;
import com.alibaba.nacos.common.tls.SelfHostnameVerifier;
import com.alibaba.nacos.common.tls.SelfTrustManager;
import com.alibaba.nacos.common.tls.TlsFileWatcher;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.common.tls.TlsSystemConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * JDK http client request implement.
 *
 * @author mai.jh
 */
public class JdkHttpClientRequest implements HttpClientRequest {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(JdkHttpClientRequest.class);
    
    private HttpClientConfig httpClientConfig;
    
    public JdkHttpClientRequest(HttpClientConfig httpClientConfig) {
        this.httpClientConfig = httpClientConfig;
        loadSSLContext();
        replaceHostnameVerifier();
        try {
            TlsFileWatcher.getInstance().addFileChangeListener(new TlsFileWatcher.FileChangeListener() {
                @Override
                public void onChanged(String filePath) {
                    loadSSLContext();
                }
            }, TlsSystemConfig.tlsClientTrustCertPath);
        } catch (IOException e) {
            LOGGER.error("add tls file listener fail", e);
        }
    }
    
    @SuppressWarnings("checkstyle:abbreviationaswordinname")
    private void loadSSLContext() {
        if (TlsSystemConfig.tlsEnable) {
            try {
                SSLContext sslcontext = SSLContext.getInstance("TLS");
                sslcontext.init(null, SelfTrustManager
                                .trustManager(TlsSystemConfig.tlsClientAuthServer, TlsSystemConfig.tlsClientTrustCertPath),
                        new java.security.SecureRandom());
                HttpsURLConnection.setDefaultSSLSocketFactory(sslcontext.getSocketFactory());
                
            } catch (NoSuchAlgorithmException e) {
                LOGGER.error("Failed to create SSLContext", e);
            } catch (KeyManagementException e) {
                LOGGER.error("Failed to create SSLContext", e);
            }
        }
    }
    
    private void replaceHostnameVerifier() {
        final HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
        HttpsURLConnection.setDefaultHostnameVerifier(new SelfHostnameVerifier(hv));
    }
    
    @Override
    public HttpClientResponse execute(URI uri, String httpMethod, RequestHttpEntity requestHttpEntity)
            throws Exception {
        final Object body = requestHttpEntity.getBody();
        final Header headers = requestHttpEntity.getHeaders();
        replaceDefaultConfig(requestHttpEntity.getHttpClientConfig());
        
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        Map<String, String> headerMap = headers.getHeader();
        if (headerMap != null && headerMap.size() > 0) {
            for (Map.Entry<String, String> entry : headerMap.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        
        conn.setConnectTimeout(this.httpClientConfig.getConTimeOutMillis());
        conn.setReadTimeout(this.httpClientConfig.getReadTimeOutMillis());
        conn.setRequestMethod(httpMethod);
        if (body != null) {
            String contentType = headers.getValue(HttpHeaderConsts.CONTENT_TYPE);
            String bodyStr = JacksonUtils.toJson(body);
            if (MediaType.APPLICATION_FORM_URLENCODED.equals(contentType)) {
                Map<String, String> map = JacksonUtils.toObj(bodyStr, HashMap.class);
                bodyStr = HttpUtils.encodingParams(map, headers.getCharset());
            }
            if (bodyStr != null) {
                conn.setDoOutput(true);
                byte[] b = bodyStr.getBytes();
                conn.setRequestProperty("Content-Length", String.valueOf(b.length));
                conn.getOutputStream().write(b, 0, b.length);
                conn.getOutputStream().flush();
                conn.getOutputStream().close();
            }
        }
        conn.connect();
        return new JdkHttpClientResponse(conn);
    }
    
    /**
     * Replace the HTTP config created by default with the HTTP config specified in the request.
     *
     * @param replaceConfig http config
     */
    private void replaceDefaultConfig(HttpClientConfig replaceConfig) {
        if (replaceConfig == null) {
            return;
        }
        this.httpClientConfig = replaceConfig;
    }
    
    @Override
    public void close() throws IOException {
    
    }
}
