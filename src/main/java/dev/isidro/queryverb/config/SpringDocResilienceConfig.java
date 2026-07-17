package dev.isidro.queryverb.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.fn.AbstractRouterFunctionVisitor;
import org.springdoc.core.providers.RouterFunctionProvider;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Works around a hard incompatibility between springdoc-openapi and the {@code QUERY} HTTP
 * method this project demonstrates: {@code org.springdoc.core.fn.RouterFunctionData
 * .getRequestMethod()} converts each route's {@code HttpMethod} to Spring MVC's {@code
 * RequestMethod} enum via an exhaustive switch — and {@code RequestMethod} has no {@code QUERY}
 * constant (it's the same closed-enum problem {@code @RequestMapping} has, documented in this
 * project's README, just biting springdoc's route introspection instead of our own routing).
 * That throws an unguarded {@code IllegalStateException}, and springdoc's default {@code
 * RouterFunctionWebMvcProvider} has no per-bean isolation around it: one route with an unknown
 * HTTP method aborts {@code /api-docs} generation for the entire application, not just that
 * route. {@code springdoc.paths-to-exclude} does not help — path exclusion is applied to the
 * already-built document, after this exception has already propagated out of route discovery.
 *
 * <p>This is a replacement for springdoc's default {@link RouterFunctionProvider} bean that
 * isolates each {@link RouterFunction} bean's traversal: a bean that fails to visit is logged and
 * skipped rather than taking down documentation for every other route.
 *
 * <p>Two things that look like the obvious way to register this <em>don't</em> work, both found
 * by testing rather than assumed:
 * <ul>
 *   <li>Naming this bean method {@code routerFunctionProvider} (matching springdoc's own) throws
 *       {@code BeanDefinitionOverrideException} at startup — bean-definition name collisions are
 *       resolved before springdoc's {@code @ConditionalOnMissingBean} ever gets evaluated.</li>
 *   <li>{@code @ConditionalOnMissingBean} on springdoc's bean method infers its target type from
 *       that method's return type — the <em>concrete</em> {@code RouterFunctionWebMvcProvider},
 *       not the {@link RouterFunctionProvider} interface. Since this bean can't be typed as
 *       {@code RouterFunctionWebMvcProvider} (see below), that condition never sees it as a
 *       match, so springdoc's own bean gets registered too. With two {@code RouterFunctionProvider}
 *       beans and no {@code @Primary}, Spring resolves the resulting ambiguity by matching the
 *       constructor parameter name in springdoc's {@code SpringDocProviders} — which is literally
 *       {@code routerFunctionProvider} — silently picking springdoc's bean over this one. Hence
 *       {@code @Primary} below: it's what actually wins the tie, regardless of bean name.</li>
 * </ul>
 *
 * <p>This duplicates (rather than extends) springdoc's {@code RouterFunctionWebMvcProvider}: its
 * {@code applicationContext} field and visitor inner class are both {@code private}, so there's
 * no subclassing hook available — the loop and visitor here are a faithful copy of its logic,
 * with a try/catch added around each bean's {@code accept()} call. See design-decisions-v2.md.
 */
@Configuration
@Slf4j
public class SpringDocResilienceConfig {

    @Bean
    @Primary
    public RouterFunctionProvider resilientRouterFunctionProvider() {
        return new ResilientRouterFunctionProvider();
    }

    private static class ResilientRouterFunctionProvider implements RouterFunctionProvider, ApplicationContextAware {

        private ApplicationContext applicationContext;

        @Override
        @SuppressWarnings("rawtypes")
        public Optional<Map<String, AbstractRouterFunctionVisitor>> getRouterFunctionPaths() {
            Map<String, RouterFunction> routerBeans = applicationContext.getBeansOfType(RouterFunction.class);
            if (CollectionUtils.isEmpty(routerBeans)) {
                return Optional.empty();
            }

            Map<String, AbstractRouterFunctionVisitor> routerFunctionVisitorMap = new HashMap<>();
            for (Map.Entry<String, RouterFunction> entry : routerBeans.entrySet()) {
                RouterFunction<?> routerFunction = entry.getValue();
                RouterFunctionVisitor visitor = new RouterFunctionVisitor();
                try {
                    routerFunction.accept(visitor);
                    routerFunctionVisitorMap.put(entry.getKey(), visitor);
                } catch (RuntimeException ex) {
                    log.warn("Skipping OpenAPI documentation for router function bean '{}' — "
                            + "failed to introspect one of its routes (likely a non-standard "
                            + "HTTP method springdoc doesn't recognize): {}",
                            entry.getKey(), ex.toString());
                }
            }
            return Optional.of(routerFunctionVisitorMap);
        }

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
            this.applicationContext = applicationContext;
        }
    }

    /** Faithful copy of springdoc's private {@code RouterFunctionWebMvcProvider.RouterFunctionVisitor}. */
    private static class RouterFunctionVisitor extends AbstractRouterFunctionVisitor
            implements RouterFunctions.Visitor, RequestPredicates.Visitor {

        @Override
        public void route(RequestPredicate predicate, HandlerFunction<?> handlerFunction) {
            this.currentRouterFunctionDatas = new ArrayList<>();
            predicate.accept(this);
            commonRoute();
        }

        @Override
        public void resources(Function<ServerRequest, Optional<Resource>> lookupFunction) {
            // Not yet needed — matches springdoc's own no-op.
        }

        @Override
        public void unknown(RouterFunction<?> routerFunction) {
            // Not yet needed — matches springdoc's own no-op.
        }

        @Override
        public void unknown(RequestPredicate predicate) {
            // Not yet needed — matches springdoc's own no-op.
        }

        @Override
        public void startNested(RequestPredicate predicate) {
            commonStartNested();
            predicate.accept(this);
        }

        @Override
        public void endNested(RequestPredicate predicate) {
            commonEndNested();
        }
    }
}
