package com.example.coalesce.aspect;

import com.example.coalesce.annotation.Coalesce;
import com.example.coalesce.coordinator.RedissonCoalesceCoordinator;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Turns a method invocation into the deterministic key string that names this call's Redis
 * objects.
 *
 * <p>Nothing makes a key "globally unique" on its own — there is one Redis behind every pod,
 * so any string every pod agrees on already refers to the same shared entry. The whole
 * engineering problem is making every pod compute the byte-identical string for the same
 * logical call, which is entirely this class's job.
 *
 * <p>Extracted from the aspect so it can be tested without Redis or a reactive chain.
 */
@Component
public class CoalesceKeyResolver {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

    public String resolve(Method method, Object[] args, Coalesce ann, HttpHeaders headers) {
        StandardEvaluationContext spelCtx = new StandardEvaluationContext();

        // DefaultParameterNameDiscoverer, not the AspectJ signature's own parameter names:
        // those are unusable unless the project was compiled with -parameters.
        String[] names = paramNames.getParameterNames(method);
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                spelCtx.setVariable(names[i], args[i]);
            }
        }

        String base = parser.parseExpression(ann.key()).getValue(spelCtx, String.class);

        String headerPart = "";
        if (ann.headerKeys().length > 0) {
            HttpHeaders resolved = headers == null ? HttpHeaders.EMPTY : headers;
            headerPart = Arrays.stream(ann.headerKeys())
                    .sorted() // declaration order must not change the key
                    .map(h -> h + "=" + Optional.ofNullable(resolved.getFirst(h)).map(String::trim).orElse(""))
                    .collect(Collectors.joining("|"));
        }

        String namespace = ann.namespace().isEmpty()
                ? method.getDeclaringClass().getSimpleName() + "." + method.getName()
                : ann.namespace();

        String raw = namespace + ":" + base + (headerPart.isEmpty() ? "" : ":" + headerPart);
        // The {} hash tag is REQUIRED for Redis Cluster and must be applied from the very
        // first implementation — retrofitting it invalidates every key already in flight.
        return RedissonCoalesceCoordinator.KEY_PREFIX + "{" + raw + "}";
    }
}
