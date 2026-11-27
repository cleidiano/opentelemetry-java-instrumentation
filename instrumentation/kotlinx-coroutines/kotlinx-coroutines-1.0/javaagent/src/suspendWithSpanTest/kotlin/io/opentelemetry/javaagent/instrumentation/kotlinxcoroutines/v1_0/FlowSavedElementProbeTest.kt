package io.opentelemetry.javaagent.instrumentation.kotlinxcoroutines.v1_0

import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.instrumentation.annotations.WithSpan
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File
import kotlin.coroutines.CoroutineContext

class FlowSavedElementProbeTest {
  companion object {
    @JvmStatic
    @RegisterExtension
    val testing = AgentInstrumentationExtension.create()
  }

  private fun attempt(name: String, body: suspend () -> List<Int>): String =
    try { "$name: OK ${runBlocking { body() }}" } catch (e: Throwable) { "$name: THROWS ${e.javaClass.simpleName}: ${e.message?.take(60)}" }

  @Test
  fun probe() {
    val saved: CoroutineContext = Context.current().asContextElement()
    val out = listOf(
      attempt("S0 plain direct emit") { flow { plainEmit(this) }.toList() },
      attempt("S1 annotated direct emit (#21159)") { flow { annotatedEmit(this) }.toList() },
      attempt("S2 plain withContext(saved){emit} inside withContext(saved)") { withContext(saved) { flow { plainSaved(this, saved) }.toList() } },
      attempt("S3 annotated withContext(saved){emit} inside withContext(saved)") { withContext(saved) { flow { annotatedSaved(this, saved) }.toList() } },
      attempt("S4 annotated withContext(saved){emit}, collected without saved") { flow { annotatedSaved(this, saved) }.toList() },
    )
    System.getenv("PROBE_OUT")?.let { File(it) }?.writeText(out.joinToString("\n") + "\n")
  }

  @WithSpan("emit") private suspend fun annotatedEmit(c: FlowCollector<Int>) = plainEmit(c)
  private suspend fun plainEmit(c: FlowCollector<Int>) = c.emit(1)
  @WithSpan("saved") private suspend fun annotatedSaved(c: FlowCollector<Int>, s: CoroutineContext) = plainSaved(c, s)
  private suspend fun plainSaved(c: FlowCollector<Int>, s: CoroutineContext) = withContext(s) { c.emit(1) }
}
