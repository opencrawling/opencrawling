/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.opencrawling.migrator.mcf.mcf.client.ManifoldCFApiException;
import org.opencrawling.runtime.discovery.DiscoveryApiException;
import org.opencrawling.runtime.dynamicjob.DynamicJobException;
import org.opencrawling.runtime.sidecar.SidecarException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers every error of the Platform API as a problem detail, including errors raised before a controller runs
 * (unknown route, wrong method, unreadable body, the management authorization interceptor). Requests outside the
 * Platform API are left to Spring's default handling, so the legacy endpoints answer exactly as before.
 */
public class ApiV1ExceptionResolver implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApiV1ExceptionResolver.class);

    private final JsonMapper json;

    public ApiV1ExceptionResolver(JsonMapper json) {
        this.json = json;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
                                         Exception exception) {
        if (!ApiV1.isPlatformRequest(request)) {
            return null;
        }
        ApiProblem problem = toProblem(exception, request);
        try {
            ProblemResponses.write(request, response, problem, json);
        } catch (Exception e) {
            log.warn("Could not write the problem response for {}: {}", request.getRequestURI(), e.getMessage());
        }
        return new ModelAndView();
    }

    static ApiProblem toProblem(Exception exception, HttpServletRequest request) {
        return switch (exception) {
            case ApiProblem problem -> problem;
            // The feature services' own exceptions, raised when a v1 endpoint calls them
            case DynamicJobException failed -> ApiProblem.fromStatus(failed.status().value(), failed.getMessage());
            case DiscoveryApiException failed -> ApiProblem.fromStatus(failed.status().value(), failed.getMessage());
            case SidecarException failed -> ApiProblem.fromStatus(failed.status().value(), failed.getMessage());
            case ManifoldCFApiException failed -> new ApiProblem(HttpStatus.BAD_GATEWAY, "upstream-error",
                    "Upstream system failed", "ManifoldCF: " + failed.getMessage());
            case MethodArgumentNotValidException invalid -> ApiProblem.invalid(fieldErrors(invalid.getBindingResult()));
            case HandlerMethodValidationException invalid -> ApiProblem.invalid(invalid.getParameterValidationResults()
                    .stream()
                    .flatMap(result -> result.getResolvableErrors().stream()
                            .map(error -> new ApiProblem.FieldError(
                                    result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                    .toList());
            case HttpMessageNotReadableException unreadable -> tooLarge(unreadable) != null
                    ? RequestSizeFilter.problem(tooLarge(unreadable).maxBytes())
                    : new ApiProblem(HttpStatus.BAD_REQUEST, "malformed-request",
                    "Malformed request", "The request body is missing, is not valid JSON, or has a value of the wrong type.");
            case MissingServletRequestParameterException missing ->
                    ApiProblem.invalid(missing.getParameterName(), "is required");
            case MissingRequestHeaderException missing ->
                    ApiProblem.invalid(missing.getHeaderName(), "header is required");
            case MethodArgumentTypeMismatchException mismatch ->
                    ApiProblem.invalid(mismatch.getName(), "has an invalid value");
            case HttpRequestMethodNotSupportedException method -> {
                ApiProblem problem = new ApiProblem(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed",
                        "Method not allowed", request.getMethod() + " is not supported on this resource.");
                if (method.getSupportedHttpMethods() != null && !method.getSupportedHttpMethods().isEmpty()) {
                    problem.header("Allow", String.join(", ", method.getSupportedHttpMethods().stream()
                            .map(Object::toString).toList()));
                }
                yield problem;
            }
            case HttpMediaTypeNotSupportedException media -> new ApiProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "unsupported-media-type", "Unsupported media type",
                    "Send " + media.getSupportedMediaTypes() + ".");
            case HttpMediaTypeNotAcceptableException media -> new ApiProblem(HttpStatus.NOT_ACCEPTABLE,
                    "not-acceptable", "Not acceptable", "This resource is available as " + media.getSupportedMediaTypes() + ".");
            case NoResourceFoundException missing -> noSuchEndpoint(request);
            case NoHandlerFoundException missing -> noSuchEndpoint(request);
            case org.springframework.web.multipart.MaxUploadSizeExceededException tooLarge -> new ApiProblem(
                    HttpStatus.CONTENT_TOO_LARGE, "body-too-large", "Request too large", "The upload is larger than allowed.");
            case MultipartException multipart -> ApiProblem.badRequest("The multipart request is invalid.");
            case AccessDeniedException denied -> ApiProblem.forbidden("You may not do this.");
            case ResponseStatusException status -> ApiProblem.fromStatus(status.getStatusCode().value(),
                    status.getReason() != null ? status.getReason() : null);
            default -> {
                log.error("Unexpected error on {} {} [request {}]", request.getMethod(), request.getRequestURI(),
                        request.getAttribute(RequestIdFilter.ATTRIBUTE), exception);
                yield new ApiProblem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                        "The request failed unexpectedly. The request id identifies it in the runtime's log.");
            }
        };
    }

    private static ApiProblem noSuchEndpoint(HttpServletRequest request) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "no-such-endpoint", "Not found",
                "There is no endpoint " + request.getMethod() + " " + request.getRequestURI()
                        + ". The API description is at /v3/api-docs/" + ApiV1.OPENAPI_GROUP + ".");
    }

    private static List<ApiProblem.FieldError> fieldErrors(BindingResult result) {
        List<ApiProblem.FieldError> errors = new ArrayList<>();
        result.getFieldErrors().forEach(error -> errors.add(new ApiProblem.FieldError(error.getField(),
                error.getDefaultMessage())));
        result.getGlobalErrors().forEach(error -> errors.add(new ApiProblem.FieldError(error.getObjectName(),
                error.getDefaultMessage())));
        return errors;
    }

    /** The size limit a body broke while it was read, if that is why it could not be read. */
    private static RequestSizeFilter.BodyTooLargeException tooLarge(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestSizeFilter.BodyTooLargeException tooLarge) {
                return tooLarge;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }
}
