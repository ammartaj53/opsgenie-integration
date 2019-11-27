package com.opsgenie.plugin.service;

import com.google.common.base.Charsets;
import com.opsgenie.plugin.listener.SendResult;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.EntityEnclosingMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.apache.logging.log4j.core.util.IOUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;

@Component
public class OpsgenieClientImpl implements OpsgenieClient {

    private final static Integer MAX_RETRY_COUNT = 5;

    private final static Long MAX_RETRY_DURATION = 40_000L;

    private final HttpClient httpClient;

    public OpsgenieClientImpl() {
        this.httpClient = new HttpClient();
        HttpConnectionManagerParams params = new HttpConnectionManagerParams();
        params.setDefaultMaxConnectionsPerHost(20);
        params.setMaxTotalConnections(20);
        params.setSoTimeout(40000);//40 sec
        params.setConnectionTimeout(10000);//10 sec
        httpClient.getHttpConnectionManager().setParams(params);
    }

    @Override
    public SendResult post(String endpoint, String apiKey, String dataAsJson) {
        PostMethod postMethod = new PostMethod(endpoint + "?apiKey=" + apiKey);
        return send(postMethod, dataAsJson);
    }

    @Override
    public SendResult put(String endpoint, String apiKey, String dataAsJson) {
        PutMethod putMethod = new PutMethod(endpoint + "?apiKey=" + apiKey);
        return send(putMethod, dataAsJson);
    }

    private SendResult executeWithRetry(HttpMethod httpMethod) {

        SendResult result = new SendResult();
        result.setSuccess(false);
        int currentRetryCount = 0;
        long retryStartTime = System.currentTimeMillis();
        int statusCode;
        String failReason = null;

        while (retryPresent(currentRetryCount, retryStartTime)) {
            try {
                currentRetryCount++;
                statusCode = httpClient.executeMethod(httpMethod);
                if (statusCode >= 200 && statusCode < 300) {
                    result.setSuccess(true);
                    break;
                } else if (!isRetryable(statusCode)) {
                    failReason = buildLogFromHttpResponse(statusCode, httpMethod);
                    break;
                } else {
                    failReason = buildLogFromHttpResponse(statusCode, httpMethod);
                }
            } catch (HttpException e) {
                //do not retry
                failReason = e.getMessage();
                break;
            } catch (IOException e) {
                //retry
                failReason = e.getMessage();
            }
        }
        httpMethod.releaseConnection();
        return result.setNumberOfAttempts(currentRetryCount)
                .setFailReason(failReason);
    }

    private boolean retryPresent(int currentRetryCount, long retryStartTime) {
        long currentTime = System.currentTimeMillis();
        try {
            Thread.sleep(currentRetryCount * 100L);
        } catch (InterruptedException e) {
            //ignored
        }
        return (currentRetryCount < MAX_RETRY_COUNT) && ((currentTime - retryStartTime) <= MAX_RETRY_DURATION);
    }

    private String buildLogFromHttpResponse(int statusCode, HttpMethod postMethod) {
        String message;
        try {
            Reader targetReader = new InputStreamReader(postMethod.getResponseBodyAsStream());
            message = IOUtils.toString(targetReader);
            targetReader.close();
        } catch (IOException e) {
            message = "Could not get responseBody";
        }
        return "Status code: " + statusCode + " " + message;
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private SendResult send(EntityEnclosingMethod httpMethod, String dataAsJson) {
        StringRequestEntity requestEntity = null;
        try {
            requestEntity = new StringRequestEntity(dataAsJson, "application/json", Charsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            //ignored
        }
        httpMethod.setRequestEntity(requestEntity);
        return executeWithRetry(httpMethod);
    }
}