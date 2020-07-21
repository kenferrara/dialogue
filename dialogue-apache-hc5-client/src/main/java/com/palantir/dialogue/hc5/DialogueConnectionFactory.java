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

package com.palantir.dialogue.hc5;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hc.client5.http.impl.io.DefaultHttpResponseParserFactory;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentLengthStrategy;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.DefaultContentLengthStrategy;
import org.apache.hc.core5.http.impl.io.DefaultHttpRequestWriterFactory;
import org.apache.hc.core5.http.io.HttpConnectionFactory;
import org.apache.hc.core5.http.io.HttpMessageParserFactory;
import org.apache.hc.core5.http.io.HttpMessageWriterFactory;

@SuppressWarnings("ImmutableEnumChecker")
enum DialogueConnectionFactory implements HttpConnectionFactory<ManagedHttpClientConnection> {
    INSTANCE;

    private static final AtomicLong COUNTER = new AtomicLong();

    private final Http1Config h1Config = Http1Config.DEFAULT;
    private final CharCodingConfig charCodingConfig = CharCodingConfig.DEFAULT;
    private final HttpMessageWriterFactory<ClassicHttpRequest> requestWriterFactory =
            DefaultHttpRequestWriterFactory.INSTANCE;
    private final HttpMessageParserFactory<ClassicHttpResponse> responseParserFactory =
            DefaultHttpResponseParserFactory.INSTANCE;
    private final ContentLengthStrategy incomingContentStrategy = DefaultContentLengthStrategy.INSTANCE;
    private final ContentLengthStrategy outgoingContentStrategy = DefaultContentLengthStrategy.INSTANCE;

    @Override
    public ManagedHttpClientConnection createConnection(Socket socket) throws IOException {
        CharsetDecoder charDecoder = null;
        CharsetEncoder charEncoder = null;
        Charset charset = this.charCodingConfig.getCharset();
        CodingErrorAction malformedInputAction = this.charCodingConfig.getMalformedInputAction() != null
                ? this.charCodingConfig.getMalformedInputAction()
                : CodingErrorAction.REPORT;
        CodingErrorAction unmappableInputAction = this.charCodingConfig.getUnmappableInputAction() != null
                ? this.charCodingConfig.getUnmappableInputAction()
                : CodingErrorAction.REPORT;
        if (charset != null) {
            charDecoder = charset.newDecoder();
            charDecoder.onMalformedInput(malformedInputAction);
            charDecoder.onUnmappableCharacter(unmappableInputAction);
            charEncoder = charset.newEncoder();
            charEncoder.onMalformedInput(malformedInputAction);
            charEncoder.onUnmappableCharacter(unmappableInputAction);
        }
        ManagedHttpClientConnection conn =
                new TracedManagedHttpClientConnection(new DialogueManagedHttpClientConnection(
                        "http-outgoing-" + COUNTER.getAndIncrement(),
                        charDecoder,
                        charEncoder,
                        h1Config,
                        incomingContentStrategy,
                        outgoingContentStrategy,
                        requestWriterFactory,
                        responseParserFactory));
        if (socket != null) {
            conn.bind(socket);
        }
        return conn;
    }
}
