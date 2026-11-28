# Kotlin Coroutines

Kotlin coroutine library instrumentation is located at
<https://github.com/open-telemetry/opentelemetry-java/tree/main/extensions/kotlin>

Coroutine context propagation is enabled by default, including across suspension and dispatcher
switches, and uses the `kotlinx-coroutines` selector.

## `@WithSpan`

Ordinary non-suspend Kotlin functions annotated with `@WithSpan` are instrumented by default
through the `opentelemetry-instrumentation-annotations` selector. This includes functions returning
`Flow`; their spans end when collection completes.

Instrumentation of functions declared with `suspend`, including suspend functions returning
`Flow`, is disabled by default and requires kotlinx-coroutines 1.6.1 or later. To enable it:

```properties
otel.instrumentation.kotlinx-coroutines-annotations.enabled=true
```

With this instrumentation enabled, collecting a `flow {}` throws
`IllegalStateException: Flow invariant is violated` if a suspend function annotated with
`@WithSpan` tail-calls `emit`. The flow stops before delivering that value
([#21159](https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/21159)).
