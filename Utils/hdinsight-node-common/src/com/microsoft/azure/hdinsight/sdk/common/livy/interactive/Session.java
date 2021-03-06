/*
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

package com.microsoft.azure.hdinsight.sdk.common.livy.interactive;

import com.microsoft.azure.hdinsight.sdk.common.HttpObservable;
import com.microsoft.azure.hdinsight.sdk.common.HttpResponse;
import com.microsoft.azure.hdinsight.sdk.common.livy.interactive.exceptions.ApplicationNotStartException;
import com.microsoft.azure.hdinsight.sdk.common.livy.interactive.exceptions.SessionNotStartException;
import com.microsoft.azure.hdinsight.sdk.common.livy.interactive.exceptions.StatementExecutionError;
import com.microsoft.azure.hdinsight.sdk.rest.livy.interactive.SessionKind;
import com.microsoft.azure.hdinsight.sdk.rest.livy.interactive.SessionState;
import com.microsoft.azure.hdinsight.sdk.rest.livy.interactive.api.PostSessions;
import com.microsoft.azuretools.azurecommons.helpers.NotNull;
import com.microsoft.azuretools.azurecommons.helpers.Nullable;
import org.apache.http.entity.StringEntity;
import rx.Observable;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static rx.exceptions.Exceptions.propagate;

public abstract class Session implements AutoCloseable, Closeable {
    private static final String REST_SEGMENT_SESSION = "sessions";

    @NotNull
    private URI baseUrl;            // Session base URL

    private int id;                 // Session ID of server

    @Nullable
    private String appId;           // Application ID of server

    @NotNull
    private HttpObservable http;    // Http connection

    @NotNull
    private String name;            // Session name

    @NotNull
    private SessionState lastState; // Last session state gotten

    @Nullable
    private List<String> lastLogs;  // Last session logs

    /*
     * Constructor
     */

    public Session(@NotNull String name, @NotNull URI baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.lastState = SessionState.NOT_STARTED;
        this.http = new HttpObservable();
    }

    public Session(@NotNull String name, @NotNull final URI baseUrl, @NotNull final String username, @NotNull final String password) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.lastState = SessionState.NOT_STARTED;
        this.http = new HttpObservable(username, password);
    }

    /*
     * Getter / Setter
     */
    @NotNull
    public String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    @NotNull
    public URI getUri() throws SessionNotStartException {
        return baseUrl.resolve(REST_SEGMENT_SESSION + "/" + String.valueOf(getId()));
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getId() throws SessionNotStartException {
        if (getLastState() == SessionState.NOT_STARTED) {
            throw new SessionNotStartException(getName() + " isn't created. Call create() firstly before getting ID.");
        }

        return id;
    }

    public Observable<String> getAppId() {
        return appId != null ?
                Observable.just(appId) :
                this.get()
                    .repeatWhen(ob -> ob.delay(1, TimeUnit.SECONDS))
                    .takeUntil(session -> session.appId != null)
                    .filter(session -> session.appId != null)
                    .timeout(3, TimeUnit.MINUTES)
                    .map(session -> {
                        if (session.appId == null || session.appId.isEmpty()) {
                            throw propagate(new ApplicationNotStartException(
                                    getName() + " application isn't started in 3 minutes."));
                        }

                        return session.appId;
                    });
    }

    private void setAppId(@Nullable String appId) {
        this.appId = appId;
    }

    @NotNull
    public abstract SessionKind getKind();

    @NotNull
    public HttpObservable getHttp() {
        return http;
    }

    @NotNull
    public SessionState getLastState() {
        return lastState;
    }

    private void setLastState(@NotNull SessionState lastState) {
        this.lastState = lastState;
    }

    public void setLastLogs(@Nullable List<String> lastLogs) {
        this.lastLogs = lastLogs;
    }

    @Nullable
    public List<String> getLastLogs() {
        return lastLogs;
    }

    /*
     * Overrides
     */
    @Override
    public void close() {
        kill().toBlocking().single();
    }

    /*
     * Helper APIs
     */
    public boolean isStarted() {
        return getLastState() != SessionState.STARTING &&
                getLastState() != SessionState.NOT_STARTED;
    }

    public boolean isStop() {
        return getLastState() == SessionState.SHUTTING_DOWN ||
                getLastState() == SessionState.NOT_STARTED ||
                getLastState() == SessionState.DEAD;
    }

    public boolean isStatementRunnable() {
        return getLastState() == SessionState.IDLE ||
                getLastState() == SessionState.BUSY;
    }

    /*
     * Observable APIs, all IO operations
     */

    /**
     * To create a session with specified kind.
     *
     * @return An updated Session instance Observable
     */
    public Observable<Session> create() {
        return createSessionRequest()
                .map(this::updateWithResponse);
    }

    private Session updateWithResponse(com.microsoft.azure.hdinsight.sdk.rest.livy.interactive.Session sessionResp) {
        this.setId(sessionResp.getId());
        this.setAppId(sessionResp.getAppId());
        this.setLastState(sessionResp.getState());
        this.setLastLogs(sessionResp.getLog());

        return this;
    }

    @NotNull
    private PostSessions preparePostSessions() {
        PostSessions postBody = new PostSessions();
        postBody.setName(getName());
        postBody.setKind(getKind());

        return postBody;
    }

    private Observable<com.microsoft.azure.hdinsight.sdk.rest.livy.interactive.Session> createSessionRequest() {
        URI uri = baseUrl.resolve(REST_SEGMENT_SESSION);

        PostSessions postBody = preparePostSessions();
        String json = postBody.convertToJson()
                .orElseThrow(() -> new IllegalArgumentException("Bad session arguments to post."));

        StringEntity entity = new StringEntity(json, StandardCharsets.UTF_8);
        entity.setContentType("application/json");

        return getHttp()
                .post(uri.toString(), entity, null, null, com.microsoft.azure.hdinsight.sdk.rest.livy.interactive.Session.class);
    }

    /**
     * To kill a session, if it's opened, cancel all running statements and close it, otherwise, do nothing
     *
     * @return an updated Session instance Observable
     */
    public Observable<Session> kill() {
        return deleteSessionRequest()
                .map(resp -> this)
                .defaultIfEmpty(this);
    }

    private Observable<HttpResponse> deleteSessionRequest() {
        URI uri;

        try {
            uri = getUri();
        } catch (SessionNotStartException e) {
            return Observable.empty();
        }

        return getHttp()
                .delete(uri.toString(), null, null);
    }

    /**
     * To get a session status.
     *
     * @return an updated Session instance Observable
     */
    public Observable<Session> get() {
        return getSessionRequest()
                .map(this::updateWithResponse)
                .defaultIfEmpty(this);
    }

    private Observable<com.microsoft.azure.hdinsight.sdk.rest.livy.interactive.Session> getSessionRequest() {
        URI uri;

        try {
            uri = getUri();
        } catch (SessionNotStartException e) {
            return Observable.empty();
        }

        return getHttp()
                .get(uri.toString(), null, null, com.microsoft.azure.hdinsight.sdk.rest.livy.interactive.Session.class);
    }

    public Observable<Map<String, String>> runStatement(@NotNull Statement statement) {
        return awaitReady()
            .flatMap(session -> statement
                    .run()
                    .map(result -> {
                        if (!result.getStatus().toLowerCase().equals("ok")) {
                            throw propagate(new StatementExecutionError(
                                    result.getEname(), result.getEvalue(), result.getTraceback()));
                        }

                        return result.getData();
                    }));
    }

    private Observable<Session> awaitReady() {
        return get()
                .map(ses -> {
                    if (ses.isStop()) {
                        throw propagate(new SessionNotStartException(
                                "Session " + getName() + " is " + getLastState() + ". " +
                                        Optional.ofNullable(ses.getLastLogs())
                                                .map(logs -> String.join("\n", logs))
                                                .orElse("")));
                    }

                    return ses;
                })
                .repeatWhen(ob -> ob.delay(1, TimeUnit.SECONDS))
                .takeUntil(Session::isStatementRunnable)
                .filter(Session::isStatementRunnable);
    }

    public Observable<Map<String, String>> runCodes(@NotNull String codes) {
        return runStatement(new Statement(this, new ByteArrayInputStream(codes.getBytes(StandardCharsets.UTF_8))));
    }

    public Observable<String> getLog() {
        throw new NotImplementedException();
    }
}
