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

package com.lidroid.xutils.http;

import android.text.TextUtils;
import com.lidroid.xutils.HttpUtils;
import com.lidroid.xutils.exception.HttpException;
import com.lidroid.xutils.http.client.HttpGetCache;
import com.lidroid.xutils.http.client.HttpRequest;
import com.lidroid.xutils.http.client.ResponseStream;
import com.lidroid.xutils.http.client.callback.DefaultHttpRedirectHandler;
import com.lidroid.xutils.http.client.callback.HttpRedirectHandler;
import com.lidroid.xutils.util.OtherUtils;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.net.UnknownHostException;

public class SyncHttpHandler {

    private final AbstractHttpClient client;
    private final HttpContext context;

    private int retriedTimes = 0;

    private String charset; // The default charset of response header info.

    private HttpRedirectHandler httpRedirectHandler;

    public void setHttpRedirectHandler(HttpRedirectHandler httpRedirectHandler) {
        this.httpRedirectHandler = httpRedirectHandler;
    }

    public SyncHttpHandler(AbstractHttpClient client, HttpContext context, String charset) {
        this.client = client;
        this.context = context;
        this.charset = charset;
    }

    private String _getMethodRequestUrl; // If current request not http "get" method, it will be null.
    private long expiry = HttpGetCache.getDefaultExpiryTime();

    public void setExpiry(long expiry) {
        this.expiry = expiry;
    }

    public ResponseStream sendRequest(HttpRequestBase request) throws HttpException {

        boolean retry = true;
        HttpRequestRetryHandler retryHandler = client.getHttpRequestRetryHandler();
        while (retry) {
            IOException exception = null;
            try {
                if (request.getMethod().equals(HttpRequest.HttpMethod.GET.toString())) {
                    _getMethodRequestUrl = request.getURI().toString();
                } else {
                    _getMethodRequestUrl = null;
                }
                if (_getMethodRequestUrl != null) {
                    String result = HttpUtils.sHttpGetCache.get(_getMethodRequestUrl);
                    if (result != null) {
                        return new ResponseStream(result);
                    }
                }
                HttpResponse response = client.execute(request, context);
                return handleResponse(response);
            } catch (UnknownHostException e) {
                exception = e;
                retry = retryHandler.retryRequest(exception, ++retriedTimes, context);
            } catch (IOException e) {
                exception = e;
                retry = retryHandler.retryRequest(exception, ++retriedTimes, context);
            } catch (NullPointerException e) {
                exception = new IOException(e.getMessage());
                retry = retryHandler.retryRequest(exception, ++retriedTimes, context);
            } catch (HttpException e) {
                throw e;
            } catch (Exception e) {
                exception = new IOException(e.getMessage());
                retry = retryHandler.retryRequest(exception, ++retriedTimes, context);
            } finally {
                if (!retry && exception != null) {
                    throw new HttpException(exception);
                }
            }
        }
        return null;
    }

    private ResponseStream handleResponse(HttpResponse response) throws HttpException, IOException {
        if (response == null) {
            throw new HttpException("response is null");
        }
        StatusLine status = response.getStatusLine();
        int statusCode = status.getStatusCode();
        if (statusCode < 300) {

            // Set charset from response header if it's exist.
            String responseCharset = OtherUtils.getCharsetFromHttpResponse(response);
            charset = TextUtils.isEmpty(responseCharset) ? charset : responseCharset;

            return new ResponseStream(response, charset, _getMethodRequestUrl, expiry);
        } else if (statusCode == 301 || statusCode == 302) {
            if (httpRedirectHandler == null) {
                httpRedirectHandler = new DefaultHttpRedirectHandler();
            }
            HttpRequestBase request = httpRedirectHandler.getDirectRequest(response);
            if (request != null) {
                return this.sendRequest(request);
            }
        } else if (statusCode == 416) {
            throw new HttpException(statusCode, "maybe the file has downloaded completely");
        } else {
            throw new HttpException(statusCode, status.getReasonPhrase());
        }
        return null;
    }
}
