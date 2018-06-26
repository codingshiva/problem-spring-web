package org.zalando.problem.spring.common;

import org.apiguardian.api.API;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.zalando.problem.*;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.Optional;

import static java.util.Arrays.asList;
import static org.apiguardian.api.API.Status.INTERNAL;
import static org.apiguardian.api.API.Status.MAINTAINED;
import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;
import static org.zalando.problem.spring.common.Lists.lengthOfTrailingPartialSubList;
import static org.zalando.problem.spring.common.MediaTypes.PROBLEM;

/**
 * <p>
 * Advice traits are simple interfaces that provide a single method with a default
 * implementation. They are used to provide {@link ExceptionHandler} implementations to be used in
 * Spring Controllers and/or in a {@link ControllerAdvice}. Clients can choose which traits they what to
 * use à la carte.
 * </p>
 * <p>
 * Advice traits are grouped in packages, based on they use cases. Every package has a composite advice trait that
 * bundles all traits of that package.
 * </p>
 *
 * @see ControllerAdvice
 * @see ExceptionHandler
 * @see Throwable
 * @see Exception
 * @see Problem
 */
@API(status = INTERNAL)
public interface AdviceTrait {

    Logger LOG = LoggerFactory.getLogger(AdviceTrait.class);

    default ThrowableProblem toProblem(final Throwable throwable) {
        final StatusType status = Optional.ofNullable(resolveResponseStatus(throwable))
                .<StatusType>map(ResponseStatusAdapter::new)
                .orElse(Status.INTERNAL_SERVER_ERROR);

        return toProblem(throwable, status);
    }

    @API(status = MAINTAINED)
    default ResponseStatus resolveResponseStatus(final Throwable type) {
        @Nullable final ResponseStatus candidate = findMergedAnnotation(type.getClass(), ResponseStatus.class);
        return candidate == null && type.getCause() != null ? resolveResponseStatus(type.getCause()) : candidate;
    }

    default ThrowableProblem toProblem(final Throwable throwable, final StatusType status) {
        return toProblem(throwable, status, Problem.DEFAULT_TYPE);
    }

    default ThrowableProblem toProblem(final Throwable throwable, final StatusType status, final URI type) {
        final ThrowableProblem problem = prepare(throwable, status, type).build();
        final StackTraceElement[] stackTrace = createStackTrace(throwable);
        problem.setStackTrace(stackTrace);
        return problem;
    }

    default ProblemBuilder prepare(final Throwable throwable, final StatusType status, final URI type) {
        return Problem.builder()
                .withType(type)
                .withTitle(status.getReasonPhrase())
                .withStatus(status)
                .withDetail(throwable.getMessage())
                .withCause(Optional.ofNullable(throwable.getCause())
                    .filter(cause -> isCausalChainsEnabled())
                    .map(this::toProblem)
                    .orElse(null));
    }

    default StackTraceElement[] createStackTrace(final Throwable throwable) {
        final Throwable cause = throwable.getCause();

        if (cause == null || !isCausalChainsEnabled()) {
            return throwable.getStackTrace();
        } else {

            final StackTraceElement[] next = cause.getStackTrace();
            final StackTraceElement[] current = throwable.getStackTrace();

            final int length = current.length - lengthOfTrailingPartialSubList(asList(next), asList(current));
            final StackTraceElement[] stackTrace = new StackTraceElement[length];
            System.arraycopy(current, 0, stackTrace, 0, length);
            return stackTrace;
        }
    }

    default boolean isCausalChainsEnabled() {
        return false;
    }

    default void log(
            @SuppressWarnings("UnusedParameters") final Throwable throwable,
            @SuppressWarnings("UnusedParameters") final Problem problem,
            final HttpStatus status) {
        if (status.is4xxClientError()) {
            LOG.warn("{}: {}", status.getReasonPhrase(), throwable.getMessage());
        } else if (status.is5xxServerError()) {
            LOG.error(status.getReasonPhrase(), throwable);
        }
    }

    default ResponseEntity<Problem> fallback(
            @SuppressWarnings("UnusedParameters") final Throwable throwable,
            @SuppressWarnings("UnusedParameters") final Problem problem,
            @SuppressWarnings("UnusedParameters") final HttpHeaders headers) {
        return ResponseEntity
                .status(HttpStatus.valueOf(Optional.ofNullable(problem.getStatus())
                        .orElse(Status.INTERNAL_SERVER_ERROR)
                        .getStatusCode()))
                .headers(headers)
                .contentType(PROBLEM)
                .body(problem);
    }

    default ResponseEntity<Problem> process(final ResponseEntity<Problem> entity) {
        return entity;
    }

}
