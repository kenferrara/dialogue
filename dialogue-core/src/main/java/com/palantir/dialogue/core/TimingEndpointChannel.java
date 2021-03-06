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

package com.palantir.dialogue.core;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.palantir.dialogue.Endpoint;
import com.palantir.dialogue.EndpointChannel;
import com.palantir.dialogue.Request;
import com.palantir.dialogue.Response;
import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class TimingEndpointChannel implements EndpointChannel {
    private final EndpointChannel delegate;
    private final Timer responseTimer;
    private final Meter ioExceptionMeter;
    private final Ticker ticker;

    TimingEndpointChannel(
            EndpointChannel delegate,
            Ticker ticker,
            TaggedMetricRegistry taggedMetrics,
            String channelName,
            Endpoint endpoint) {
        this.delegate = delegate;
        this.ticker = ticker;
        ClientMetrics metrics = ClientMetrics.of(taggedMetrics);
        this.responseTimer = metrics.response()
                .channelName(channelName)
                .serviceName(endpoint.serviceName())
                .build();
        this.ioExceptionMeter = metrics.responseError()
                .channelName(channelName)
                .serviceName(endpoint.serviceName())
                .reason("IOException")
                .build();
    }

    static EndpointChannel create(Config cf, EndpointChannel delegate, Endpoint endpoint) {
        return new TimingEndpointChannel(
                delegate, cf.ticker(), cf.clientConf().taggedMetricRegistry(), cf.channelName(), endpoint);
    }

    @Override
    public ListenableFuture<Response> execute(Request request) {
        long beforeNanos = ticker.read();
        ListenableFuture<Response> response = delegate.execute(request);

        return DialogueFutures.addDirectCallback(response, new FutureCallback<Response>() {
            @Override
            public void onSuccess(Response _result) {
                updateResponseTimer();
            }

            @Override
            public void onFailure(Throwable throwable) {
                updateResponseTimer();
                if (throwable instanceof IOException) {
                    ioExceptionMeter.mark();
                }
            }

            private void updateResponseTimer() {
                responseTimer.update(ticker.read() - beforeNanos, TimeUnit.NANOSECONDS);
            }
        });
    }

    @Override
    public String toString() {
        return "TimingEndpointChannel{" + delegate + '}';
    }
}
