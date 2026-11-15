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

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An error answer of the Platform API: an RFC 9457 problem detail.
 *
 * <p>{@link #code()} is the problem type's short name (for example {@code not-found}, {@code precondition-failed},
 * {@code job-already-running}); the Platform API reference lists them. Members a problem type adds, such as
 * {@code currentEtag} or {@code executionId}, are in {@link #extensions()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ApiProblem {

    /** One invalid value of the request. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldError(String field, String message) {}

    private String type;
    private String title;
    private int status;
    private String detail;
    private String instance;
    private String requestId;
    private List<FieldError> errors = List.of();
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    public ApiProblem() {}

    public ApiProblem(int status, String type, String title, String detail) {
        this.status = status;
        this.type = type;
        this.title = title;
        this.detail = detail;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getInstance() { return instance; }
    public void setInstance(String instance) { this.instance = instance; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public List<FieldError> getErrors() { return errors; }
    public void setErrors(List<FieldError> errors) { this.errors = errors != null ? List.copyOf(errors) : List.of(); }

    @JsonAnyGetter
    public Map<String, Object> extensions() { return extensions; }

    @JsonAnySetter
    public void extension(String name, Object value) { extensions.put(name, value); }

    /** The problem type's short name, for example {@code not-found}; null if the problem has no type. */
    public String code() {
        if (type == null) {
            return null;
        }
        // ".../Platform-API#problem-not-found" (the anchor of its documentation), or ".../problems/not-found"
        int anchor = type.indexOf("#problem-");
        if (anchor >= 0) {
            return type.substring(anchor + "#problem-".length());
        }
        int slash = type.lastIndexOf('/');
        return slash >= 0 ? type.substring(slash + 1) : type;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder().append(status).append(' ')
                .append(title != null ? title : "Error");
        if (detail != null) {
            text.append(": ").append(detail);
        }
        for (FieldError error : errors) {
            text.append("; ").append(error.field()).append(' ').append(error.message());
        }
        if (requestId != null) {
            text.append(" (request ").append(requestId).append(')');
        }
        return text.toString();
    }
}
