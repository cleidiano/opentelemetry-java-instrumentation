/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.kotlinxcoroutines.v1_0

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.extension.kotlin.getOpenTelemetryContext
import io.opentelemetry.instrumentation.annotations.WithSpan
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

// The span's context element is the only element the callee adds, under the OpenTelemetry key, so
// withContext with a saved OpenTelemetry element replaces all of it.
class TailCallWithSpanContextElementTest {

  companion object {
    @JvmStatic
    @RegisterExtension
    val testing = AgentInstrumentationExtension.create()

    private val pool = Executors.newSingleThreadExecutor()

    @JvmStatic
    @AfterAll
    fun shutdown() {
      pool.shutdown()
    }
  }

  @Test
  fun `flow collected inside a saved element keeps working when an annotated helper reuses it`() {
    val saved = Context.current().asContextElement()

    val expected = runBlocking { withContext(saved) { flow { unannotatedEmitWith(this, saved) }.toList() } }
    assertThat(expected).containsExactly(1)
    assertThat(runBlocking { withContext(saved) { flow { annotatedEmitWith(this, saved) }.toList() } })
      .isEqualTo(expected)

    testing.waitAndAssertTraces(
      { trace -> trace.hasSpansSatisfyingExactly({ it.hasName("emit-with").hasNoParent() }) },
    )
  }

  @WithSpan("emit-with")
  private suspend fun annotatedEmitWith(collector: FlowCollector<Int>, element: CoroutineContext) = unannotatedEmitWith(collector, element)

  private suspend fun unannotatedEmitWith(collector: FlowCollector<Int>, element: CoroutineContext) = withContext(element) { collector.emit(1) }

  @Test
  fun `reattaching the span context from the continuation keeps the scopes balanced`() {
    val expected = runBlocking { reattach() }
    assertThat(expected).isEqualTo(Reattached(sameThread = true, restored = true, pool = true, afterSuspend = true))
    assertThat(runBlocking { annotatedReattach() }).isEqualTo(expected)

    assertThat(Span.current().spanContext.isValid).isFalse()
    assertThat(pool.submit<Boolean> { Span.current().spanContext.isValid }.get()).isFalse()

    testing.waitAndAssertTraces(
      { trace -> trace.hasSpansSatisfyingExactly({ it.hasName("reattach").hasNoParent() }) },
    )
  }

  @WithSpan("reattach")
  private suspend fun annotatedReattach(): Reattached = reattach()

  private data class Reattached(val sameThread: Boolean, val restored: Boolean, val pool: Boolean, val afterSuspend: Boolean)

  // Makes the context the runtime holds for the coroutine current again, as code that propagates it
  // would, on this thread and on another one.
  private suspend fun reattach(): Reattached {
    val context = coroutineContext.getOpenTelemetryContext()
    val before = Span.current().spanContext
    val expected = Span.fromContext(context).spanContext

    val sameThread = context.makeCurrent().use { Span.current().spanContext == expected }
    val restored = Span.current().spanContext == before

    val seen = CompletableFuture<Boolean>()
    pool.execute(context.wrap(Runnable { seen.complete(Span.current().spanContext == expected) }))
    val onPool = seen.get(10, TimeUnit.SECONDS)

    withContext(Dispatchers.Default) { delay(5) }
    return Reattached(sameThread, restored, onPool, Span.current().spanContext == before)
  }
}
