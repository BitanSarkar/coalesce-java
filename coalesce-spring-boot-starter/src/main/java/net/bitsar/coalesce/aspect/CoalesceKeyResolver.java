package net.bitsar.coalesce.aspect;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.bitsar.coalesce.annotation.CoalesceAttributes;
import net.bitsar.coalesce.core.CoalesceKeys;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpHeaders;

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
public class CoalesceKeyResolver {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer paramNames = new DefaultParameterNameDiscoverer();

    /** Parsing SpEL allocates a syntax tree; the expression per method never changes. */
    private final Map<String, Expression> expressions = new ConcurrentHashMap<>();

    /**
     * @param method  the annotated method
     * @param args    its arguments for this invocation
     * @param attrs   the annotation with placeholders already resolved
     * @param headers request headers, or empty when there are none to fold in
     * @return the Redis key naming this call
     */
    public String resolve(Method method, Object[] args, CoalesceAttributes attrs, HttpHeaders headers) {
        StandardEvaluationContext spelCtx = new StandardEvaluationContext();

        // DefaultParameterNameDiscoverer, not the AspectJ signature's own parameter names:
        // those are unusable unless the project was compiled with -parameters.
        String[] names = paramNames.getParameterNames(method);
        if (names == null && attrs.keyExpression().contains("#")) {
            // Since Spring 6.1 the debug-symbol fallback is gone, so without -parameters
            // there are no names at all and every #variable in the expression evaluates to
            // null. Left alone that silently collapses every argument onto ONE key, which
            // serves one caller's data to another. Refuse instead.
            throw new IllegalStateException(describe(method, attrs)
                    + " references parameters by name, but no parameter names are available."
                    + " Compile with -parameters (Spring Boot's Gradle and Maven plugins set it for you).");
        }
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                spelCtx.setVariable(names[i], args[i]);
            }
        }

        String base = expressions
                .computeIfAbsent(attrs.keyExpression(), parser::parseExpression)
                .getValue(spelCtx, String.class);
        if (base == null || base.isBlank()) {
            // A blank key is never what the caller meant, and it groups unrelated calls
            // together exactly like the null case above.
            throw new IllegalStateException(describe(method, attrs) + " resolved to "
                    + (base == null ? "null" : "an empty string") + ".");
        }

        String headerPart = "";
        if (!attrs.headerKeys().isEmpty()) {
            HttpHeaders resolved = headers == null ? HttpHeaders.EMPTY : headers;
            headerPart = attrs.headerKeys().stream()
                    .sorted() // declaration order must not change the key
                    .map(h -> h + "=" + Optional.ofNullable(resolved.getFirst(h)).map(String::trim).orElse(""))
                    .collect(Collectors.joining("|"));
        }

        // Already the effective namespace: CoalesceAttributeResolver derives it once, so
        // the key and the runtime toggle cannot disagree about what this method is called.
        String raw = attrs.namespace() + ":" + base + (headerPart.isEmpty() ? "" : ":" + headerPart);
        // The {} hash tag is REQUIRED for Redis Cluster and must be applied from the very
        // first implementation — retrofitting it invalidates every key already in flight.
        return CoalesceKeys.KEY_PREFIX + "{" + raw + "}";
    }

    /** The expression alone is not enough to find the annotation; name the method too. */
    private static String describe(Method method, CoalesceAttributes attrs) {
        return "@Coalesce key \"" + attrs.keyExpression() + "\" on "
                + method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
