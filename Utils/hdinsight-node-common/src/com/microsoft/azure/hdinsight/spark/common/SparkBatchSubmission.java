/**
 * Copyright (c) Microsoft Corporation
 * <p/>
 * All rights reserved.
 * <p/>
 * MIT License
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.microsoft.azure.hdinsight.spark.common;

import com.microsoft.azure.hdinsight.common.HDInsightLoader;
import com.microsoft.azure.hdinsight.common.StreamUtil;
import com.microsoft.azure.hdinsight.sdk.common.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import rx.Observable;
import rx.schedulers.Schedulers;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class SparkBatchSubmission {

    private static String userAgentName;

    private SparkBatchSubmission() {
        String installID = HDInsightLoader.getHDInsightHelper().getInstallationId();
        String userAgentSource = SparkBatchSubmission.class.getClassLoader().getClass().getName().toLowerCase().contains("intellij")
                ? "Azure Toolkit for IntelliJ " : "Azure Toolkit for Eclipse ";
        userAgentName = userAgentSource + installID;
    }

    // Singleton Instance
    private static SparkBatchSubmission instance = null;

    public static SparkBatchSubmission getInstance() {
        if(instance == null){
            synchronized (SparkBatchSubmission.class){
                if(instance == null){
                    instance = new SparkBatchSubmission();
                }
            }
        }

        return instance;
    }

    private CredentialsProvider credentialsProvider =  new BasicCredentialsProvider();

    /**
     * Set http request credential using username and password
     * @param username : username
     * @param password : password
     */
    public void setCredentialsProvider(String username, String password){
        credentialsProvider.setCredentials(new AuthScope(AuthScope.ANY), new UsernamePasswordCredentials(username, password));
    }

    public CredentialsProvider getCredentialsProvider() {
        return credentialsProvider;
    }

    public HttpResponse getHttpResponseViaGet(String connectUrl) throws IOException {
        CloseableHttpClient httpclient = HttpClients.custom().setDefaultCredentialsProvider(credentialsProvider).build();

        HttpGet httpGet = new HttpGet(connectUrl);
        httpGet.addHeader("Content-Type", "application/json");
        httpGet.addHeader("User-Agent", userAgentName);
        httpGet.addHeader("X-Requested-By", "ambari");
        try(CloseableHttpResponse response = httpclient.execute(httpGet)) {
            return StreamUtil.getResultFromHttpResponse(response);
        }
    }

    public HttpResponse getHttpResponseViaHead(String connectUrl) throws IOException {
        CloseableHttpClient httpclient = HttpClients.custom().setDefaultCredentialsProvider(getCredentialsProvider())
                .build();

        HttpHead httpHead = new HttpHead(connectUrl);
        httpHead.addHeader("Content-Type", "application/json");
        httpHead.addHeader("User-Agent", getUserAgentName());
        httpHead.addHeader("X-Requested-By", "ambari");

        // WORKAROUND: https://github.com/Microsoft/azure-tools-for-java/issues/1358
        // The Ambari local account will cause Kerberos authentication initializing infinitely.
        // Set a timer here to cancel the progress.
        Observable.timer(3, TimeUnit.SECONDS, Schedulers.io())
                  .take(1)
                  .subscribe(i -> httpHead.abort());

        try(CloseableHttpResponse response = httpclient.execute(httpHead)) {
            return StreamUtil.getResultFromHttpResponse(response);
        }
    }

    public String getUserAgentName() {
        return userAgentName;
    }

    /**
     * get all batches spark jobs
     * @param connectUrl : eg http://localhost:8998/batches
     * @return response result
     * @throws IOException
     */
    public HttpResponse getAllBatchesSparkJobs(String connectUrl)throws IOException{
        return getHttpResponseViaGet(connectUrl);
    }

    /**
     * create batch spark job
     * @param connectUrl : eg http://localhost:8998/batches
     * @param submissionParameter : spark submission parameter
     * @return response result
     */
    public HttpResponse createBatchSparkJob(String connectUrl, SparkSubmissionParameter submissionParameter)throws IOException{
        CloseableHttpClient httpclient = HttpClients.custom().setDefaultCredentialsProvider(credentialsProvider).build();
        HttpPost httpPost = new HttpPost(connectUrl);
        httpPost.addHeader("Content-Type", "application/json");
        httpPost.addHeader("User-Agent", userAgentName);
        httpPost.addHeader("X-Requested-By", "ambari");
        StringEntity postingString =new StringEntity(submissionParameter.serializeToJson());
        httpPost.setEntity(postingString);
        try(CloseableHttpResponse response = httpclient.execute(httpPost)) {
            return StreamUtil.getResultFromHttpResponse(response);
        }
    }

    /**
     * get batch spark job status
     * @param connectUrl : eg http://localhost:8998/batches
     * @param batchId : batch Id
     * @return response result
     * @throws IOException
     */
    public HttpResponse getBatchSparkJobStatus(String connectUrl, int batchId)throws IOException{
        return getHttpResponseViaGet(connectUrl + "/" + batchId);
    }

    /**
     * kill batch job
     * @param connectUrl : eg http://localhost:8998/batches
     * @param batchId : batch Id
     * @return response result
     * @throws IOException
     */
    public HttpResponse killBatchJob(String connectUrl, int batchId)throws IOException {
        CloseableHttpClient httpclient = HttpClients.custom().setDefaultCredentialsProvider(credentialsProvider).build();
        HttpDelete httpDelete = new HttpDelete(connectUrl +  "/" + batchId);
        httpDelete.addHeader("User-Agent", userAgentName);
        httpDelete.addHeader("Content-Type", "application/json");
        httpDelete.addHeader("X-Requested-By", "ambari");

        try(CloseableHttpResponse response = httpclient.execute(httpDelete)) {
            return StreamUtil.getResultFromHttpResponse(response);
        }
    }

    /**
     * get batch job full log
     * @param connectUrl : eg http://localhost:8998/batches
     * @param batchId : batch Id
     * @return response result
     * @throws IOException
     */
    public HttpResponse getBatchJobFullLog(String connectUrl, int batchId)throws IOException {
        return getHttpResponseViaGet(String.format("%s/%d/log?from=%d&size=%d", connectUrl, batchId, 0, Integer.MAX_VALUE));
    }
}
