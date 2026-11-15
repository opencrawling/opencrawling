/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.sdk.v1;

import org.opencrawling.sdk.exceptions.OpenCrawlingApiException;

/**
 * The Platform API answered with an error. {@link #problem()} has the details: the problem type ({@link
 * ApiProblem#code()}), what went wrong, the invalid fields and the request id to quote when reporting it.
 */
public class ApiProblemException extends OpenCrawlingApiException {

    private final transient ApiProblem problem;

    public ApiProblemException(ApiProblem problem, String responseBody) {
        super(problem.getStatus(), responseBody);
        this.problem = problem;
    }

    public ApiProblem problem() {
        return problem;
    }

    /** The problem type's short name, for example {@code not-found}. */
    public String code() {
        return problem.code();
    }

    @Override
    public String getMessage() {
        return problem.toString();
    }
}
