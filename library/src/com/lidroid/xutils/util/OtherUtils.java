/*
 * Copyright (c) 2013. wyouflf (wyouflf@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lidroid.xutils.util;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.protocol.HTTP;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * Created by wyouflf on 13-8-30.
 */
public class OtherUtils {
    private OtherUtils() {
    }

    public static String getFileNameFromHttpResponse(HttpResponse response) {
        if (response == null) return null;
        String result = null;
        Header header = response.getFirstHeader("Content-Disposition");
        if (header != null) {
            for (HeaderElement element : header.getElements()) {
                NameValuePair fileNamePair = element.getParameterByName("filename");
                if (fileNamePair != null) {
                    result = fileNamePair.getValue();
                    result = CharsetUtils.toCharset(result, HTTP.UTF_8, result.length());//尝试转换乱码
                    break;
                }
            }
        }
        return result;
    }

    public static String getCharsetFromHttpResponse(HttpResponse response) {
        if (response == null) return null;
        String result = null;
        Header header = response.getEntity().getContentType();
        if (header != null) {
            for (HeaderElement element : header.getElements()) {
                NameValuePair charsetPair = element.getParameterByName("charset");
                if (charsetPair != null) {
                    result = charsetPair.getValue();
                    break;
                }
            }
        }
        return result;
    }

    public static StackTraceElement getCurrentStackTraceElement() {
        return Thread.currentThread().getStackTrace()[3];
    }

    public static StackTraceElement getCallerStackTraceElement() {
        return Thread.currentThread().getStackTrace()[4];
    }

    private static TrustManager[] trustAllCerts;

    public static void trustAllSSLForHttpsURLConnection() {
        // Create a trust manager that does not validate certificate chains
        if (trustAllCerts == null) {
            trustAllCerts = new TrustManager[]{new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            }};
        }
        // Install the all-trusting trust manager
        final SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        } catch (Exception e) {
            LogUtils.e(e.getMessage(), e);
        }
        HttpsURLConnection.setDefaultHostnameVerifier(org.apache.http.conn.ssl.SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
    }
}
