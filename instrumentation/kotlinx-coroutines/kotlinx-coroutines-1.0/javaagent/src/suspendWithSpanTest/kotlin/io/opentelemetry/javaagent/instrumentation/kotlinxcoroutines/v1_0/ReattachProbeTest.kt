package io.opentelemetry.javaagent.instrumentation.kotlinxcoroutines.v1_0

import io.opentelemetry.api.trace.Span
import io.opentelemetry.extension.kotlin.getOpenTelemetryContext
import io.opentelemetry.instrumentation.annotations.WithSpan
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class ReattachProbeTest {
  companion object {
    @JvmStatic
    @RegisterExtension
    val testing = AgentInstrumentationExtension.create()
  }

  private val pool = Executors.newSingleThreadExecutor()

  @Test
  fun probe() {
    val out = mutableListOf<String>()
    fun rec(name: String, body: () -> String) {
      out += "$name: " + try { body() } catch (e: Throwable) { "THROWS ${e.javaClass.simpleName}: ${e.message?.take(80)}" }
    }
    rec("U0 unannotated") { runBlocking { reattach() } }
    rec("A0 annotated  ") { runBlocking { annotated() } }
    out += "main thread after: valid=${Span.current().spanContext.isValid}"
    out += "pool thread after: " + pool.submit<String> { "valid=${Span.current().spanContext.isValid}" }.get()
    pool.shutdown()
    System.getenv("PROBE_OUT")?.let { File(it) }?.writeText(out.joinToString("\n") + "\n")
  }

  @WithSpan("callee")
  private suspend fun annotated(): String = reattach()

  private suspend fun reattach(): String {
    val oc = coroutineContext.getOpenTelemetryContext()
    val before = Span.current().spanContext
    val sb = StringBuilder()
    val scope = oc.makeCurrent()
    try {
      sb.append("sameThreadInside=${Span.current().spanContext == Span.fromContext(oc).spanContext} ")
    } finally {
      scope.close()
    }
    sb.append("afterClose=${Span.current().spanContext == before} ")
    val seen = CompletableFuture<String>()
    pool.execute(oc.wrap(Runnable { seen.complete("poolInside=${Span.current().spanContext == Span.fromContext(oc).spanContext}") }))
    sb.append(seen.get(5, TimeUnit.SECONDS)).append(' ')
    withContext(Dispatchers.Default) { delay(5) }
    sb.append("afterSuspend=${Span.current().spanContext == before}")
    return sb.toString()
  }
}
