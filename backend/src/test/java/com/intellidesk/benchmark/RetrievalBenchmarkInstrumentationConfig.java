package com.intellidesk.benchmark;

import com.intellidesk.retrieval.RetrievalHydrator;
import com.intellidesk.retrieval.RetrievalResult;
import com.intellidesk.retrieval.VectorRetriever;
import com.intellidesk.retrieval.fusion.HybridRetriever;
import com.intellidesk.retrieval.keyword.KeywordResult;
import com.intellidesk.retrieval.keyword.KeywordRetriever;
import com.intellidesk.retrieval.rerank.RerankClient;
import com.intellidesk.retrieval.rerank.RerankService;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Adds transparent benchmark-only observation proxies to the real production bean graph.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RetrievalBenchmarkInstrumentationConfig {

    @Bean
    @ConditionalOnExpression(
            "'${intellidesk.benchmark.formal:false}' == 'true' or "
                    + "'${intellidesk.benchmark.preflight:false}' == 'true'")
    static BeanPostProcessor retrievalBenchmarkPathBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof VectorRetriever) {
                    return proxy(bean, invocation -> {
                        Object result = invocation.proceed();
                        if ("retrieve".equals(invocation.getMethod().getName())) {
                            RetrievalBenchmarkPathProbe.recordVector(retrievalResults(result));
                        }
                        return result;
                    });
                }
                if (bean instanceof KeywordRetriever) {
                    return proxy(bean, invocation -> {
                        Object result = invocation.proceed();
                        if ("retrieve".equals(invocation.getMethod().getName())) {
                            RetrievalBenchmarkPathProbe.recordBm25(keywordResults(result));
                        }
                        return result;
                    });
                }
                if (bean instanceof HybridRetriever) {
                    return proxy(bean, invocation -> {
                        Object result = invocation.proceed();
                        if ("retrieve".equals(invocation.getMethod().getName())) {
                            RetrievalBenchmarkPathProbe.recordHybrid(retrievalResults(result));
                        }
                        return result;
                    });
                }
                if (bean instanceof RetrievalHydrator) {
                    return proxy(bean, invocation -> {
                        Object result = invocation.proceed();
                        if ("hydrate".equals(invocation.getMethod().getName())) {
                            RetrievalBenchmarkPathProbe.recordHydration(retrievalResults(result));
                        }
                        return result;
                    });
                }
                if (bean instanceof RerankService) {
                    return proxy(bean, invocation -> {
                        if ("rerank".equals(invocation.getMethod().getName())) {
                            RetrievalBenchmarkPathProbe.recordRerankInput(retrievalResults(invocation.getArguments()[1]));
                        }
                        return invocation.proceed();
                    });
                }
                if (bean instanceof RerankClient) {
                    return proxy(bean, invocation -> {
                        if ("rerank".equals(invocation.getMethod().getName())) {
                            RetrievalBenchmarkPathProbe.recordProviderCall();
                        }
                        return invocation.proceed();
                    });
                }
                return bean;
            }
        };
    }

    private static Object proxy(Object target, MethodInterceptor interceptor) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return factory.getProxy();
    }

    @SuppressWarnings("unchecked")
    private static List<RetrievalResult> retrievalResults(Object value) {
        return (List<RetrievalResult>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<KeywordResult> keywordResults(Object value) {
        return (List<KeywordResult>) value;
    }
}
