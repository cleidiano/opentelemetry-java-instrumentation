/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.kotlinxcoroutines.v1_0.instrumentationannotations

import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.context.Scope
import java.util.ArrayDeque

// Not a companion object: its synthetic constructor references DefaultConstructorMarker, which is
// not public in older kotlin-stdlib versions.
private object ScopeHandoffKey {
  val applied = ThreadLocal<ScopeHandoff?>()
}

// The innermost handoff open on this thread, if the runtime is running a coroutine with a callee's
// context on it.
fun currentScopeHandoff(): ScopeHandoff? = ScopeHandoffKey.applied.get()

// One handoff on a thread. Scopes deferred to it are closed when the runtime restores the callee's
// context, most recent first.
class ScopeHandoff internal constructor(private val previous: ScopeHandoff?) {
  private var deferred: ArrayDeque<Scope>? = null
  private var restored = false

  fun defer(scope: Scope) {
    val scopes = deferred ?: ArrayDeque<Scope>().also { deferred = it }
    scopes.push(scope)
  }

  // Returns whether the scope was still deferred, that is, not closed by the runtime yet.
  fun undefer(scope: Scope): Boolean = deferred?.remove(scope) == true

  internal fun restore(): Boolean {
    // UndispatchedCoroutine can replay the restore of a snapshot taken without a dispatcher.
    if (restored) {
      return false
    }
    restored = true
    deferred?.let { scopes ->
      while (!scopes.isEmpty()) {
        scopes.pop().close()
      }
    }
    ScopeHandoffKey.applied.set(previous)
    return true
  }
}

// The span's context, as given to the span's thread context element. Making it current opens a
// handoff that the caller's scopes are deferred to; closing the scope closes them first, then the
// span's own scope.
class HandoffContext(private val delegate: Context) : Context {
  override fun <V : Any?> get(key: ContextKey<V>): V? = delegate.get(key)

  override fun <V : Any?> with(k1: ContextKey<V>, v1: V): Context = delegate.with(k1, v1)

  override fun makeCurrent(): Scope {
    val handoff = ScopeHandoff(ScopeHandoffKey.applied.get())
    ScopeHandoffKey.applied.set(handoff)
    val scope = delegate.makeCurrent()
    return Scope {
      if (handoff.restore()) {
        scope.close()
      }
    }
  }
}
