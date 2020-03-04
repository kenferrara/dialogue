/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.conjure.java.dialogue.serde;

import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.palantir.conjure.java.api.errors.RemoteException;
import com.palantir.conjure.java.api.errors.UnknownRemoteException;
import com.palantir.dialogue.Channel;
import com.palantir.dialogue.Clients;
import com.palantir.dialogue.Deserializer;
import com.palantir.dialogue.Endpoint;
import com.palantir.dialogue.Request;
import com.palantir.logsafe.UnsafeArg;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Package private internal API. */
enum DefaultClients implements Clients {
    INSTANCE;

    private static final Logger log = LoggerFactory.getLogger(DefaultClients.class);

    @Override
    public <T> ListenableFuture<T> call(
            Channel channel, Endpoint endpoint, Request request, Deserializer<T> deserializer) {
        return Futures.transform(
                channel.execute(endpoint, request), deserializer::deserialize, MoreExecutors.directExecutor());
    }

    @Override
    @SuppressWarnings("ThrowError") // match behavior of Futures.getUnchecked(Future)
    public <T> T block(ListenableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!future.cancel(true)) {
                Futures.addCallback(future, CancelListener.INSTANCE, MoreExecutors.directExecutor());
            }
            throw new SafeRuntimeException("Interrupted waiting for future", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause.getMessage();

            // TODO(jellis): can consider propagating other relevant exceptions (eg: HttpConnectTimeoutException)
            // see HttpClientImpl#send(HttpRequest req, BodyHandler<T> responseHandler)
            if (cause instanceof RemoteException) {
                throw newRemoteException((RemoteException) cause);
            }

            if (cause instanceof UnknownRemoteException) {
                throw newUnknownRemoteException((UnknownRemoteException) cause);
            }

            // This matches the behavior in Futures.getUnchecked(Future)
            if (cause instanceof Error) {
                throw new ExecutionError(message, (Error) cause);
            } else {
                throw new UncheckedExecutionException(message, cause);
            }
        }
    }

    // Need to create a new exception so our current stacktrace is included in the exception
    private static RemoteException newRemoteException(RemoteException remoteException) {
        RemoteException newException = new RemoteException(remoteException.getError(), remoteException.getStatus());
        newException.initCause(remoteException);
        return newException;
    }

    private static UnknownRemoteException newUnknownRemoteException(UnknownRemoteException cause) {
        UnknownRemoteException newException = new UnknownRemoteException(cause.getStatus(), cause.getBody());
        newException.initCause(cause);
        return newException;
    }

    enum CancelListener implements FutureCallback<Object> {
        INSTANCE;

        @Override
        public void onSuccess(@Nullable Object result) {
            if (result instanceof Closeable) {
                try {
                    ((Closeable) result).close();
                } catch (IOException | RuntimeException e) {
                    log.info(
                            "Failed to close result of {} after the call was canceled",
                            UnsafeArg.of("result", result),
                            e);
                }
            }
        }

        @Override
        public void onFailure(Throwable throwable) {
            log.info("Canceled call failed", throwable);
        }
    }
}