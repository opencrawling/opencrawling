/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
package org.opencrawling.runtime.api.v1;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Caps the size of Platform API request bodies (OWASP API4, unrestricted resource consumption): a body larger than
 * {@code maxBytes} answers {@code 413 body-too-large}, whether it announces its length or not. Multipart uploads have
 * their own limits ({@code spring.servlet.multipart.*}).
 */
public class RequestSizeFilter extends OncePerRequestFilter {

    /** Thrown while reading a body that turns out larger than the cap; mapped to {@code 413}. */
    public static final class BodyTooLargeException extends IOException {
        private final long maxBytes;

        BodyTooLargeException(long maxBytes) {
            super("The request body is larger than " + maxBytes + " bytes.");
            this.maxBytes = maxBytes;
        }

        public long maxBytes() {
            return maxBytes;
        }
    }

    private final long maxBytes;
    private final JsonMapper json;

    public RequestSizeFilter(long maxBytes, JsonMapper json) {
        this.maxBytes = maxBytes;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiV1.isPlatformRequest(request)
                || (request.getContentType() != null
                        && request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            ProblemResponses.write(request, response, problem(maxBytes), json);
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    static ApiProblem problem(long maxBytes) {
        return new ApiProblem(HttpStatus.CONTENT_TOO_LARGE, "body-too-large", "Request too large",
                "Request bodies may have at most " + maxBytes + " bytes.").with("maxBytes", maxBytes);
    }

    /** Counts what is read and stops past the cap, for bodies that do not announce their length (chunked). */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        /** The capped stream as text, so a consumer that reads characters cannot pass the cap either. */
        @Override
        public java.io.BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    encoding != null ? java.nio.charset.Charset.forName(encoding) : java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream in = super.getInputStream();
                stream = new ServletInputStream() {
                    private long read;

                    private int count(int n) throws BodyTooLargeException {
                        if (n > 0) {
                            read += n;
                            if (read > maxBytes) {
                                throw new BodyTooLargeException(maxBytes);
                            }
                        }
                        return n;
                    }

                    @Override
                    public int read() throws IOException {
                        int b = in.read();
                        if (b >= 0) {
                            count(1);
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        return count(in.read(buffer, offset, length));
                    }

                    @Override
                    public boolean isFinished() {
                        return in.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return in.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        in.setReadListener(listener);
                    }
                };
            }
            return stream;
        }
    }
}
