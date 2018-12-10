/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 Elastic and contributors
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package co.elastic.apm.agent.impl;

import co.elastic.apm.agent.MockReporter;
import co.elastic.apm.agent.configuration.CoreConfiguration;
import co.elastic.apm.agent.configuration.SpyConfiguration;
import co.elastic.apm.agent.context.LifecycleListener;
import co.elastic.apm.agent.impl.error.ErrorCapture;
import co.elastic.apm.agent.impl.sampling.ConstantSampler;
import co.elastic.apm.agent.impl.stacktrace.StacktraceConfiguration;
import co.elastic.apm.agent.impl.transaction.Span;
import co.elastic.apm.agent.impl.transaction.TraceContext;
import co.elastic.apm.agent.impl.transaction.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stagemonitor.configuration.ConfigurationRegistry;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ElasticApmTracerTest {

    private ElasticApmTracer tracerImpl;
    private MockReporter reporter;
    private ConfigurationRegistry config;

    @BeforeEach
    void setUp() {
        reporter = new MockReporter();
        config = SpyConfiguration.createSpyConfig();
        tracerImpl = new ElasticApmTracerBuilder()
            .configurationRegistry(config)
            .reporter(reporter)
            .build();
    }

    @Test
    void testThreadLocalStorage() {
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            assertThat(tracerImpl.currentTransaction()).isSameAs(transaction);
            Span span = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span.activateInScope()) {
                assertThat(tracerImpl.currentSpan()).isSameAs(span);
                assertThat(span.isChildOf(transaction)).isTrue();
                span.end();
            }
            assertThat(tracerImpl.currentSpan()).isNull();
            transaction.end();
        }
        assertThat(tracerImpl.currentTransaction()).isNull();
    }

    @Test
    void testNestedSpan() {
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            assertThat(tracerImpl.currentTransaction()).isSameAs(transaction);
            Span span = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span.activateInScope()) {
                assertThat(tracerImpl.currentSpan()).isSameAs(span);
                assertThat(span.isChildOf(transaction)).isTrue();
                Span nestedSpan = tracerImpl.activeSpan().createSpan();
                try (Scope nestedSpanScope = nestedSpan.activateInScope()) {
                    assertThat(tracerImpl.currentSpan()).isSameAs(nestedSpan);
                    assertThat(nestedSpan.isChildOf(span)).isTrue();
                    nestedSpan.end();
                }
                span.end();
            }
            assertThat(tracerImpl.currentSpan()).isNull();
            transaction.end();
        }
        assertThat(tracerImpl.currentTransaction()).isNull();
        assertThat(reporter.getSpans()).hasSize(2);
    }

    @Test
    void testDisableStacktraces() {
        when(tracerImpl.getConfig(StacktraceConfiguration.class).getSpanFramesMinDurationMs()).thenReturn(0L);
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            Span span = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span.activateInScope()) {
                span.end();
            }
            transaction.end();
        }
        assertThat(reporter.getFirstSpan().getStacktrace()).isNull();
    }

    @Test
    void testEnableStacktraces() throws InterruptedException {
        when(tracerImpl.getConfig(StacktraceConfiguration.class).getSpanFramesMinDurationMs()).thenReturn(-1L);
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            Span span = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span.activateInScope()) {
                Thread.sleep(10);
                span.end();
            }
            transaction.end();
        }
        assertThat(reporter.getFirstSpan().getStacktrace()).isNotNull();
    }

    @Test
    void testDisableStacktracesForFastSpans() {
        when(tracerImpl.getConfig(StacktraceConfiguration.class).getSpanFramesMinDurationMs()).thenReturn(100L);
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            Span span = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span.activateInScope()) {
                span.end();
            }
            transaction.end();
        }
        assertThat(reporter.getFirstSpan().getStacktrace()).isNull();

    }

    @Test
    void testEnableStacktracesForSlowSpans() throws InterruptedException {
        when(tracerImpl.getConfig(StacktraceConfiguration.class).getSpanFramesMinDurationMs()).thenReturn(1L);
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            Span span = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span.activateInScope()) {
                Thread.sleep(10);
                span.end();
            }
            transaction.end();
        }
        assertThat(reporter.getFirstSpan().getStacktrace()).isNotNull();
    }

    @Test
    void testRecordException() {
        tracerImpl.captureException(new Exception("test"));
        assertThat(reporter.getErrors()).hasSize(1);
        ErrorCapture error = reporter.getFirstError();
        assertThat(error.getException()).isNotNull();
        assertThat(error.getTraceContext().hasContent()).isFalse();
    }

    @Test
    void testRecordExceptionWithTrace() {
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            transaction.getContext().getRequest()
                .addHeader("foo", "bar")
                .withMethod("GET")
                .getUrl()
                .withPathname("/foo");
            tracerImpl.currentTransaction().captureException(new Exception("test"));
            assertThat(reporter.getErrors()).hasSize(1);
            ErrorCapture error = reporter.getFirstError();
            assertThat(error.getTraceContext().isChildOf(transaction.getTraceContext())).isTrue();
            assertThat(error.getContext().getRequest().getHeaders().get("foo")).isEqualTo("bar");
            transaction.end();
        }
    }

    @Test
    void testEnableDropSpans() {
        when(tracerImpl.getConfig(CoreConfiguration.class).getTransactionMaxSpans()).thenReturn(1);
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            Span span = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span.activateInScope()) {
                assertThat(span.isSampled()).isTrue();
                span.end();
            }
            Span span2 = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span2.activateInScope()) {
                assertThat(span2.isSampled()).isFalse();
                span2.end();
            }
            transaction.end();
        }
        assertThat(reporter.getFirstTransaction().isSampled()).isTrue();
        assertThat(reporter.getFirstTransaction().getSpanCount().getDropped().get()).isEqualTo(1);
        assertThat(reporter.getFirstTransaction().getSpanCount().getStarted().get()).isEqualTo(1);
        assertThat(reporter.getSpans()).hasSize(1);
    }

    @Test
    void testDisable() {
        when(config.getConfig(CoreConfiguration.class).isActive()).thenReturn(false);
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            assertThat(tracerImpl.currentTransaction()).isSameAs(transaction);
            assertThat(transaction.isSampled()).isFalse();
            Span span = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span.activateInScope()) {
                assertThat(tracerImpl.currentSpan()).isSameAs(span);
                assertThat(tracerImpl.currentSpan()).isNotNull();
            }
            assertThat(tracerImpl.activeSpan()).isSameAs(transaction);
            transaction.end();
        }
        assertThat(tracerImpl.currentTransaction()).isNull();
        assertThat(reporter.getTransactions()).isEmpty();
        assertThat(reporter.getSpans()).isEmpty();
    }

    @Test
    void testDisableMidTransaction() {
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            assertThat(tracerImpl.currentTransaction()).isSameAs(transaction);
            Span span = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span.activateInScope()) {
                when(config.getConfig(CoreConfiguration.class).isActive()).thenReturn(false);
                span.withName("test");
                assertThat(span.getName().toString()).isEqualTo("test");
                assertThat(tracerImpl.currentSpan()).isSameAs(span);
                assertThat(span.isChildOf(transaction)).isTrue();
                span.end();
            }
            Span span2 = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span2.activateInScope()) {
                when(config.getConfig(CoreConfiguration.class).isActive()).thenReturn(false);
                span2.withName("test2");
                assertThat(span2.getName().toString()).isEqualTo("test2");
                assertThat(tracerImpl.currentSpan()).isSameAs(span2);
                assertThat(span2.isChildOf(transaction)).isTrue();
                span2.end();
            }
            assertThat(tracerImpl.currentSpan()).isNull();
            transaction.end();
        }
        assertThat(tracerImpl.currentTransaction()).isNull();
        assertThat(reporter.getSpans()).hasSize(2);
        assertThat(reporter.getFirstTransaction()).isSameAs(transaction);
    }

    @Test
    void testSamplingNone() throws IOException {
        config.getConfig(CoreConfiguration.class).getSampleRate().update(0.0, SpyConfiguration.CONFIG_SOURCE_NAME);
        Transaction transaction = tracerImpl.startTransaction();
        try (Scope scope = transaction.activateInScope()) {
            transaction.setUser("1", "jon.doe@example.com", "jondoe");
            Span span = tracerImpl.activeSpan().createSpan();
            try (Scope spanScope = span.activateInScope()) {
                span.end();
            }
            transaction.end();
        }
        // we do report non-sampled transactions (without the context)
        assertThat(reporter.getTransactions()).hasSize(1);
        assertThat(reporter.getSpans()).hasSize(0);
        assertThat(reporter.getFirstTransaction().getContext().getUser().getEmail()).isNull();
    }

    @Test
    void testLifecycleListener() {
        final AtomicBoolean startCalled = new AtomicBoolean();
        final AtomicBoolean stopCalled = new AtomicBoolean();
        final ElasticApmTracer tracer = new ElasticApmTracerBuilder()
            .configurationRegistry(config)
            .reporter(reporter)
            .lifecycleListeners(Collections.singletonList(new LifecycleListener() {
                @Override
                public void start(ElasticApmTracer tracer) {
                    startCalled.set(true);
                }

                @Override
                public void stop() {
                    stopCalled.set(true);
                }
            }))
            .build();
        assertThat(startCalled).isTrue();
        assertThat(stopCalled).isFalse();

        tracer.stop();
        assertThat(stopCalled).isTrue();
    }

    @Test
    void testTransactionWithParentReference() {
        final String traceContextHeader = "00-0af7651916cd43dd8448eb211c80319c-b9c7c989f97918e1-01";
        final Transaction transaction = tracerImpl.startTransaction(TraceContext.fromTraceparentHeader(), traceContextHeader, ConstantSampler.of(false), 0);
        // the traced flag in the header overrides the sampler
        assertThat(transaction.isSampled()).isTrue();
        assertThat(transaction.getTraceContext().getParentId().toString()).isEqualTo("b9c7c989f97918e1");
        assertThat(transaction.getTraceContext().getTraceId().toString()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        transaction.end(1);
        assertThat(reporter.getTransactions()).hasSize(1);
        assertThat(reporter.getSpans()).hasSize(0);
    }

    @Test
    void testTimestamps() {
        final Transaction transaction = tracerImpl.startTransaction(TraceContext.fromTraceparentHeader(), null, ConstantSampler.of(true), 0);

        final Span span = transaction.createSpan(10);
        span.end(20);
        transaction.end(30);

        assertThat(transaction.getTimestamp()).isEqualTo(0);
        assertThat(transaction.getDuration()).isEqualTo(0.03);
        assertThat(span.getTimestamp()).isEqualTo(10);
        assertThat(span.getDuration()).isEqualTo(0.01);
    }

    @Test
    void testStartSpanAfterTransactionHasEnded() {
        final Transaction transaction = tracerImpl.startTransaction(TraceContext.asRoot(), null);
        final TraceContext transactionTraceContext = transaction.getTraceContext().copy();
        transaction.end();
        transaction.resetState();

        tracerImpl.activate(transactionTraceContext);
        try {
            assertThat(tracerImpl.currentSpan()).isNull();
            final Span span = tracerImpl.startSpan(TraceContext.fromActiveSpan(), tracerImpl);
            assertThat(span).isNotNull();
            try (Scope scope = span.activateInScope()) {
                assertThat(tracerImpl.currentTransaction()).isNull();
                assertThat(tracerImpl.currentSpan()).isSameAs(span);
            } finally {
                span.end();
            }
        } finally {
            tracerImpl.deactivate(transactionTraceContext);
        }
        assertThat(tracerImpl.getActive()).isNull();
    }
}