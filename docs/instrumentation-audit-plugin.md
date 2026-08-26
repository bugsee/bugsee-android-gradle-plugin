# Bytecode Instrumentation Audit — bugsee-android-gradle-plugin 4.0.5

Date: 2026-08-25. Trigger: customer report (multi-module app, SDK 5.9.1/plugin 3.6 →
SDK 7.1.3/plugin 4.0.5) — three failures traced to compile-time instrumentation, plus
two direct questions about scoping instrumentation away from local unit tests.

Scope: every ASM lane under `src/main/kotlin/com/bugsee/android/gradle/instrumentation/**`,
the `compose-compiler-plugin` IR transforms, their injected SDK-side targets
(read-only inspection of the SDK repo), and AGP 8.6.0 internals (decompiled from the
Gradle cache) for the unit-test-classpath question.

Verification key: **CONFIRMED** = proven by test, decompilation, or complete code trace
(the proof is stated). **SUSPECTED** = plausible from code reading, needs more evidence.

---

## 1. Per-instrumentation table

"JVM-unit-test impact" = what happens when host-app unit tests (which AGP runs against
the ASM-instrumented production classes — see §4.2) execute the injected code with the
Android framework stubbed.

| Lane (key) | Matches / injects | JVM-unit-test impact | Verifier risk | Notes |
|---|---|---|---|---|
| **OkHttp** (`okhttp`) | `INVOKESTATIC BugseeOkHttpInterceptor.addIfAbsent(Builder)` immediately before every `OkHttpClient$Builder.build()`; when the SDK ships `BugseeOkHttpWebSockets`, replaces `newWebSocket(Request, WebSocketListener)` call sites (virtual on `OkHttpClient`, interface on `WebSocket$Factory`) with the capturing static. Skips `com.bugsee.*`, `okhttp3.*`. | **Changes behavior** (customer issue 2). A real application interceptor is inserted into every built client; `Builder`/`interceptors()` state visibly changes; the WS factory call is replaced. With the current SDK both are pass-through when the SDK is not launched (`sConfig == null → chain.proceed`), but the interceptor is still *present* and its failure mode is failed requests, not skipped capture. | None. Both rewrites are stack-neutral (`addIfAbsent` consumes+returns the Builder; WS rewrite is 3 refs→1 either shape). `COPY_FRAMES` correct. `OkHttpClient`→`WebSocket$Factory` interface assignability is not verifier-checked (JVMS), so no frame issue. | Runtime idempotency via `addIfAbsent` (newBuilder-copies covered). Subclass receivers (`class MyClient : OkHttpClient()`) are missed — capture gap only. |
| **HttpEngine** (`http_engine`) | Around `android.net.http.HttpEngine.newUrlRequestBuilder` (wraps the callback arg via DUP_X2/POP/DUP2_X1/POP), `UrlRequest.Builder.setHttpMethod`/`addHeader` (DUP/DUP2 + observer call), `setUploadDataProvider` (SWAP-wrap-SWAP), `UrlRequest.start()` (pre-call). Skips `com.bugsee.*`. | **Inert.** `android.net.http` does not exist off-device; injected code only runs if the original call site runs, which it cannot in a JVM test. | Transient stack growth → correctly uses `COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS`. Stack choreography hand-verified (all category-1 refs) and covered by `HttpEngineClassVisitorTest` incl. harness verify. | Adapter correlates builder→method/headers via a `ThreadLocal`; configuring one request across threads loses correlation (data-quality only, SUSPECTED minor). Key is snake_case: Gradle property is `bugsee.instrumentation.http_engine` (DSL accepts both spellings). |
| **Log** (`log`) | Owner-swap: `android/util/Log.{v,d,i,w,e,wtf,println}` (exact descriptor set) → `BugseeLogAdapter`, same name/descriptor. Skips `com.bugsee.*`, `android.*`. | **Near-inert.** Adapter is disabled by default and forwards to `android.util.Log` — identical "not mocked" semantics under plain JUnit, identical output under Robolectric. Adapter's static init and disabled path are framework-free. | None (identical stack shape). `COPY_FRAMES` correct. | Descriptor allowlist is complete for the public Log surface it intends. |
| **Thread** (`thread`) | `BugseeThreadAdapter.registerThread()` as first instruction of `run()V` in `Runnable` implementers / `Thread` subclasses; synthetic `run()` (`registerThread(); super.run()`) generated for HandlerThread subclasses lacking `run()`. Skips `com.bugsee.*`, `android.*`. | **Crashes on SDK ≤ 7.1.3** (`android.system.Os.gettid` throws off-device from inside the host app's own `new Thread(...).start()`); **inert** with SDK commit `e99d7cd22` (guards added 2026-08-25, unreleased at audit time). | Entry injection: none (no stack effect). Synthetic run(): **CONFIRMED defect, FIXED** — could override a `final run()` in an intermediate class → load-time `VerifyError` (§2.2). | Abstract/native `run()` safe (injection lives in `visitCode`, never called for them). |
| **MainThreadMisuse** (`mainThreadMisuse`) | Zero-stack-effect `INVOKESTATIC checkXxx()V` immediately before: `FileInputStream`/`RandomAccessFile`/`FileOutputStream` ctors (incl. already-remapped Bugsee wrappers), `URL.openConnection/openStream`, `Socket` ctors/`connect`, `okhttp3/Call.execute`, `kotlinx runBlocking`, `SQLiteDatabase` write/query methods (name-only), `SharedPreferences$Editor.commit`. Skips `com.bugsee.*`, `android.*`. | **Crashed on SDK ≤ 7.1.3** — customer issue 1: `Looper.getMainLooper()` is null under JUnit, `isMainThread()` dereferenced it (NPE at `checkSharedPrefs` from ordinary app code). **No-op** with SDK `e99d7cd22`. | None. `COPY_FRAMES` correct (no transient stack use). Injecting before a paired `NEW`/`<init>` while the uninitialized ref is on the operand stack is verifier-legal. | Root cause of issue 1 is SDK-side and is fixed there; the plugin behavior (injecting into any test-reachable app code) is by design of AGP's pipeline — see §4.2. |
| **OperationDispatch** (`operationDispatch`) | (a) Type-remap of `NEW java/io/File{Input,Output}Stream` + the LIFO-paired `<init>` to Bugsee wrapper streams (`super(...)` calls in user subclasses correctly left alone); (b) `onXxxOperationStart/End("op", null)` straight-line around `RandomAccessFile` ctor, URL/Socket/`Call.execute`, `SQLiteDatabase` methods, `Editor.commit`. Skips `com.bugsee.*`, `android.*`. | **Near-inert.** No observers registered off-device → dispatch is an empty CoW-list loop; wrapper streams resolve `NoOpSpan` (catch-all) and delegate to the real stream. Observable difference: `getClass()` of a stream is `BugseeFileInputStream` (tests asserting exact class break). | Injected LDC+ACONST_NULL grows the stack → correctly `COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS`. NEW/`<init>` remap pairing is verifier-correct per JVMS §4.10.1.9 (well-commented, tested). | **CONFIRMED defect, NOT fixed (design in §2.4): the `End` call is skipped when the guarded call throws** — leaks APM spans and can mis-attribute later pops. Wrapper ctor coverage verified complete against the JDK set ((File),(String),(FD),(+append)). `FileInputStream::new` method refs (invokedynamic) are not remapped — capture gap only. |
| **ComposeInput** (`composeInput`) | `BugseeComposeInputAdapter.onComposeTouch(this, event)` at entry of `AndroidComposeView.dispatchTouchEvent(MotionEvent)Z` only (single class in the `androidx.compose.ui` JAR). | **Inert in practice** — the unit-test classpath consumes dependency JARs through the unit-test component's own (empty) instrumentation config, so the instrumented `AndroidComposeView` is used by the APK, not by host tests. Adapter itself is framework-free list dispatch anyway. | `ALOAD 0/1` + INVOKESTATIC at entry; `COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS` set. Safe. | Gating scans only the app module's *directly declared* configurations for `androidx.compose` (no project-dep recursion, unlike `DependencyDetector`) — compose arriving solely via a feature module silently disables the lane (SUSPECTED gap, capture-loss only). |
| **AppStartupTracing** (`startupTier`, tier-driven) | Tiered: MINIMAL wraps Application/ContentProvider init methods with `on{Application,Provider}Start/End` + per-return End + appended catch-any; STANDARD adds per-call wraps (narrow try/catch **prepended** to the exception table) on Initializer/ComponentRegistrar/Configuration.Provider too; DETAILED adds natural-loop wraps (CFG/dominators, exception edges excluded); FULL adds `@BugseeTrace` pickup with an annotation-peek buffer. Denylist: `com.bugsee.`, `android.`, `kotlin(x).`, `java.`, compose-runtime, JDK internals. | **Executes under Robolectric** (Robolectric instantiates `Application` and calls `onCreate`). `BugseeAppStartupDispatcher` is contractually no-throw (`emit`/`safeLog` catch `Throwable`) → no crash; cost is buffering of startup events per test JVM. Plain JUnit: only runs if the test constructs an Application/provider. | The most complex lane; layer-ordering invariant (per-call TCBs at table head, catch-any appended last) is enforced with runtime checks, suspend/synthetic/bridge/abstract/native are skipped, sibling-loop mutation uses a two-phase plan, and every tier has transform tests that run `CheckClassAdapter` + `SimpleVerifier`. **Audited sound.** | Ordering interplay: it does not skip calls to `BugseeOperationDispatcher` when wrapping calls (only its own dispatcher owner), so at STANDARD+ the op-dispatch injections that ran earlier in the visitor chain get their own call spans — span noise, not a bug (SUSPECTED minor). Version-gated (`≥7.0.0-beta11`) *and* artifact-probed (`SdkSymbolAvailability`). |
| **ExtensionsInit** (tier-driven, gated by `optimizeExtensionsLoading`) | Rewrites the single SDK class `com.bugsee.library.BugseeInitProvider#initializeExtensions()V`: before every `RETURN`, one `try { register<Name>Extension() } catch (Throwable) {}` per extension whose `<provider>` the manifest task stripped. | Inert for tests (target lives in the SDK JAR; not executed off-device). **On-device critical**: it is the only registration path once providers are stripped. | try/catch + GOTO with `COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS`. Covered by `ExtensionsInitClassVisitorTest` incl. verify. | **CONFIRMED defect, FIXED (§2.3): the manifest task stripped providers even when this injection was disabled** (global instrumentation off, or the target excluded). Remaining gap: no symbol/version gate for SDKs predating `initializeExtensions()` (§3.6). |
| **Compose tag/secure** (IR, `compose`/`composeSecure`) | Kotlin IR transform (not ASM): wraps the `Modifier` argument of calls inside named `@Composable` functions with `.bugseeTag("<name>")`; wraps password-`visualTransformation` TextField calls with `.bugseeSecure()`. | **Active in test code too**: `isApplicable` does not filter compilations, so unit/androidTest Kotlin source with `@Composable` functions is also transformed; Robolectric Compose tests observe extra modifier/semantics nodes (SUSPECTED source of subtle test diffs). | n/a (IR). Correctness risk is null-receivers, not frames. | **CONFIRMED shipped-fix correction — see §2.1 (headline finding).** |
| **Ktor / Cronet** (`ktor`, `cronet`) | **No bytecode lane exists.** These DSL keys only drive dependency auto-add; capture happens inside the SDK extensions at runtime. (Ktor-on-OkHttp WebSockets are additionally caught by the OkHttp lane's `WebSocket$Factory` rewrite.) | No compile-time injection → no JUnit exposure beyond having the AARs on the classpath. | n/a | Worth telling the customer explicitly: 2 of the 12 names they fear carry no injected code at all. |

---

## 2. Confirmed defects

### 2.1 ★ HEADLINE — the committed Compose null-Modifier fix did NOT cover the real Compose lowering shape (FIXED now, previously believed fixed)

**This corrects work shipped earlier today as commit `22aa3a5` ("fix(compose): don't
chain onto a Modifier argument that is null at runtime"). That commit's guard was
incomplete and the 4.0.5 customer crash (issue 3) was still live after it.**

- `compose-compiler-plugin/src/{legacyIr,modernIr}/.../BugseeComposeIrExtension.kt`

**Independent verification (step 1 of the correction).** Decompiled
`ComposerParamTransformer.defaultArgumentFor` from
`org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable` **2.1.0, 2.2.10,
2.3.0 and 2.4.0** (Gradle cache jars). All four emit, for an omitted parameter:

```
IrCompositeImpl(dv.startOffset, dv.endOffset, dv.type,
                IrStatementOrigin.DEFAULT_VALUE,
                listOf(dv))     // dv = IrConstImpl.defaultValueForType(...) → null const for reference types
```

i.e. an **`IrComposite` wrapping the null constant** — never a bare `IrConst`. The
committed guard was `this is IrConst && this.value == null`, which cannot match an
`IrComposite`; the parallel research was **correct**. The `NullableChild(null, true)`
regression case in the variant matrix passes a *source-level* null literal (a genuine
`IrConst`) — a proxy shape; the matrix compiles against a stub Compose with no Compose
compiler plugin, so real default lowering never runs there and the test could not catch
the gap.

**Fix.** `isNullConstant()` replaced by `isDefaultedOrNullArgument()` in BOTH source
sets: matches (a) a bare null `IrConst`, (b) an `IrComposite` with origin
`DEFAULT_VALUE`, (c) defensively, a composite whose single statement is a null const.
Skip-the-site semantics unchanged (the callee's `$default` fixup overwrites whatever we
pass, so injecting there is dead code — and chaining is an NPE).

**Proof / test-gap closure (steps 3–4).**
`compose-compiler-plugin/src/test/.../DefaultValueLoweringInteropTest.kt` +
`ComposeDefaultLoweringSimulator.kt`: a test-only compiler plugin replicating the
byte-exact composite shape is loaded **ahead of** the real Bugsee plugin in a real
`K2JVMCompiler` run; the compiled fixture is executed. With the old guard the run dies
with the customer's exact signature —
`NullPointerException: Parameter specified as non-null is null: method …bugseeTag, parameter <this>`
(verified by temporarily reverting the guard: test fails with that output; restored:
passes). The three possible outcomes (crash / spurious TAG / correct skip-with-explicit-site-still-tagged)
discriminate guard bug, plugin-order loss, and over-broad guard respectively.

*What this proves and what it does not:* it is an executed end-to-end repro of the
production IR shape **on the k21 (Kotlin 2.1) line**, which is where `src/test`
compiles. For 2.2/2.3/2.4 the evidence is: (a) the decompiled shape is identical on all
four compiler versions, (b) the patched modernIr compiles and passes the full
`composeVariantMatrix` (all variants, real compilers, executed output — rebuilt jars
verified fresh). A faithful *executed* composite repro on 2.2+ would require per-line
simulator builds inside the matrix harness; not done, stated here rather than implied.
The misleading "this is the shape Compose leaves behind" comment on the proxy fixture
was corrected in `ComposeVariantMatrixTest.kt`.

Defense-in-depth note: the SDK side (commit `e99d7cd22`, same day) also made
`bugseeTag`/`bugseeSecure` accept a nullable receiver with a `Modifier` fallback, so
even an old plugin against the new SDK no longer crashes. Both sides are needed —
plugin-side skip for old SDKs, SDK-side tolerance for old plugins.

### 2.2 Thread lane: synthetic `run()` could override a `final run()` → load-time VerifyError (FIXED)

- `instrumentation/thread/ThreadClassVisitor.kt`

For `class B extends A` where `A extends HandlerThread` declares `public final void
run()`, the lane generated `public void run()` into B (B has no own `run()` and
transitively extends HandlerThread) — overriding a final method, which ART/JVM rejects
at **class definition** with `VerifyError`, crashing the host app even if the thread is
never started. The factory only sees superclass *names* (`ClassData`), so finality
cannot be checked.

**Fix:** generate the synthetic `run()` only when the **immediate** superclass is
`android/os/HandlerThread` (whose `run()` is known non-final). No coverage loss in the
ordinary case: an intermediate HandlerThread subclass is itself in instrumentation
scope and receives its own injection, which subclasses inherit.

**Proof:** new `ThreadClassVisitorTest` — reproduces the `VerifyError` with a real
classloader on the pre-fix bytecode shape (final-`run()` base + synthetic-run child →
`Class.forName` throws `VerifyError`; fixed visitor output loads fine), pins the
direct-subclass generation, the no-generation-for-indirect case, and the entry
injection, with harness verification.

### 2.3 Extension `<provider>` stripping ignored the instrumentation gates → extensions silently dead (FIXED)

- `BugseePlugin.kt`, new `manifest/ExtensionStripGate.kt`

`BugseeManifestTask` stripped every Bugsee extension `<provider>` from the merged
manifest keyed **only** on `optimizeExtensionsLoading` (default true), while the
compensating `ExtensionsInitInstrumentation` bytecode injection additionally required
(a) instrumentation globally enabled and (b) the target class not matching the user's
`excludes`. Under either mismatch, an APK shipped with providers stripped and no
inlined registration — **every extension (NDK crash reporting, feedback, compose,
remoting …) silently never registers at runtime.**

This interacts directly with the customer report: `-Pbugsee.instrumentation.enabled=false`
is the natural "keep instrumentation away from my unit tests" switch (and a plausible
support answer); before this fix, using it on a build whose APK is shipped would have
disabled all extensions with no signal. Likewise, `InstrumentationException`'s help
text tells a blocked consumer to add an `excludes` entry for the failing class — if
that class is `BugseeInitProvider`, the advice itself would have killed extensions.

**Fix:** the strip decision now goes through `ExtensionStripGate.shouldStrip(dslFlag,
globallyEnabled, excludes)`, resolved before manifest-task registration and passed into
the task, so strip and injection share one gate. **Proof:** traced both code paths
(strip: `BugseeManifestTask.execute` keyed only on the task property; injection:
`isGloballyEnabled()` guard around the registrar + factory exclude check); semantics
pinned by `ExtensionStripGateTest` (default strips; instrumentation-off, target-exclude,
and broad-glob cases disarm; unrelated excludes keep it armed). The plugin-level wiring
itself is not integration-tested — this repo cannot apply AGP in unit tests (gradle-api
only on the test classpath); flagged in §5.

### 2.4 OperationDispatch: `End` never fires when the guarded call throws (CONFIRMED, not fixed — design below)

- `instrumentation/operation_dispatch/OperationDispatchClassVisitor.kt` (`visitMethodInsn`)

`onXxxOperationStart` / original call / `onXxxOperationEnd` are emitted straight-line
with **no try/catch**. If `execSQL` throws an `SQLException`, `openConnection` an
`IOException`, `Call.execute` anything — the `End` is skipped. SDK-side consequence
(traced in `DatabasePerformanceProvider`): `onOperationStart` pushes a span on a
per-thread stack; `onOperationEnd` pops **unconditionally when the stack is non-empty**
(deliberately, to avoid leaks across `stop()`); `onOperationStart` also *skips* the
push when no transaction is active. So an exception-orphaned span (a) stays unfinished,
and (b) can later be popped and "finished" by an unrelated `End` whose own `Start`
didn't push — mis-attributed durations, not just a leak.

**Why not fixed in this pass:** the correct shape is the per-call try/catch that
`TopLevelCallWrapper` already implements — `tryStart/call/tryEnd/End/GOTO after/
handler:End/ATHROW` with the narrow catch-any entry **prepended** to the exception
table so it wins over enclosing user handlers. This lane is a *streaming* visitor:
streamed `visitTryCatchBlock` calls can only **append** to the table (the original
entries are visited first), so a streamed handler loses priority to any user handler
enclosing the call — the `End` would still be skipped exactly in the user-caught case.
Doing it right means buffering methods into `MethodNode`s (as the startup lane does),
which is a deliberate bytecode/perf reshape across all consumers, not an audit-scoped
patch. Interim mitigation options: SDK-side orphan finalization at transaction end
(the startup lane's folder already does this for its spans), or accepting the
uncaught-path-only streamed handler as a partial fix with the caveat documented.

### 2.5 Injected SDK entry points crashed under stubbed Android on SDK ≤ 7.1.3 (customer issues 1 & 3 — root cause SDK-side; fixed there today)

For completeness of this audit's ledger: `BugseeMainThreadGuardAdapter.isMainThread()`
(null `Looper.getMainLooper()` → the exact NPE in issue 1) and
`BugseeThreadAdapter.registerThread()` (`Os.gettid` throws off-device) were hardened in
SDK commit `e99d7cd22` (2026-08-25, branch `fix/instrumentation-jvm-safety`), each with
a revert-verified test. The remaining injected targets were audited there and here:
`BugseeLogAdapter` (disabled path is framework-free), `BugseeAppStartupDispatcher`
(no-throw contract), `BugseeOperationDispatcher` (empty observer loop),
`BugseeFile{Input,Output}Stream` (catch-all → `NoOpSpan`), `BugseeHttpEngineAdapter`
(unreachable off-device), `BugseeOkHttpInterceptor.intercept` (null-config
pass-through), `BugseeOkHttpWebSockets.newWebSocket` (null-config pass-through) — all
sound off-device *in the current SDK tree*. Until an SDK release carries `e99d7cd22`,
customers on ≤ 7.1.3 will keep hitting issue 1 regardless of plugin version.

---

## 3. Suspected issues (need more evidence / lower severity)

1. **OkHttp lane + MockWebServer "zero recorded requests" (issue 2) — exact 7.1.3
   mechanism unverified.** The current interceptor source is pass-through when the SDK
   is not launched, which would let MockWebServer traffic flow. The customer's symptom
   is consistent with either (a) the 7.1.3-shipped `intercept()` lacking that
   hardening and failing the call, or (b) their tests asserting on
   builder/interceptor-list state. Someone with the 7.1.3 release artifact should
   diff `BugseeOkHttpInterceptor.intercept` before replying. Regardless of mechanism,
   the lane genuinely **mutates test-visible state** (interceptor present, WS factory
   call replaced) — that part is CONFIRMED and inherent to the design.
2. **Compose IR transform runs on test compilations.** `isApplicable(kotlinCompilation)`
   never inspects the compilation; `@Composable`s in `test`/`androidTest` sources get
   tags/secure wraps. Harmless for most suites, observable in Robolectric semantics
   assertions. A one-line name filter would scope it out — product decision (§5).
3. **`hasComposeDependency` (ComposeInput + compiler-plugin gating) is non-recursive** —
   compose brought in only by a project sub-module is not detected; both compose lanes
   silently disable. Capture loss, no crash.
4. **Startup STANDARD tier wraps `BugseeOperationDispatcher` calls** injected by the
   op-dispatch lane earlier in the visitor chain (self-skip covers only its own
   dispatcher class) — span noise in init methods that do guarded I/O.
5. **`CatchingMethodVisitor` does not guard pre-body events** (`visitParameter`,
   `visitAnnotation*`, `visitAttribute`). No Bugsee visitor emits these today; a
   failure there would fail the build unattributed rather than corrupt output. Cosmetic.
6. **ExtensionsInit has no SDK-symbol/version gate.** Pairing plugin 4.x with an SDK
   whose `BugseeInitProvider` predates `initializeExtensions()` strips providers and
   injects nothing (method never matches) → extensions dead. `SdkClassProbe` can only
   check class presence, not methods; a `BugseeSdkVersion` floor (like the startup
   lane's) is the cheap guard.
7. **HttpEngine adapter thread-affinity** (§1 table) — cross-thread builder
   configuration loses method/header correlation.
8. **R8-minified third-party AARs** are instrumented like everything else under
   `InstrumentationScope.ALL`; no equivalent of Sentry's `~~R8` constant-pool-marker
   hard-skip. Assessment: our call-site lanes are shape-agnostic (match individual
   instructions, stack-neutral or frame-recomputed) so the classic "javac-shape
   assumption" failure class mostly doesn't apply; the exposure is concentrated in the
   startup lane's whole-method rewrites — which only touch Application/
   ContentProvider/Initializer-kind classes and `@BugseeTrace` (FULL tier), rarely
   R8-minified library code. Real but bounded. Caveat on the remedy: AGP's
   `AsmClassVisitorFactory` never exposes raw class bytes or the constant pool to
   `isInstrumentable`/`createClassVisitor`, so the marker cannot be read the way
   Sentry's own transform (which owns its byte stream) reads it; an AGP-based
   implementation would need a heuristic (e.g. treat `visitSource == null` +
   `SourceDebugExtension` absence as minified) or a separate artifact-transform
   pre-scan feeding an excludes file. Recommended as an investigation item, not a
   quick fix (§5).

**Checked and found sound** (explicitly, since absence of findings is a finding):
frames-computation mode per lane (COPY_FRAMES only where injections are provably
stack-neutral; COMPUTE_FRAMES everywhere stack grows or handlers are added);
`NEW`/`<init>` pairing incl. subclass `super(...)` (JVMS-verified logic + tests);
uninitialized-this/uninitialized-ref rules (no lane injects into constructors before
`super()`; try-catch regions never require uninit refs in *locals*); long/double slot
indexing (no lane indexes past slot 1, and only on known-shape methods); multi-return,
throw and loop paths in the startup wrappers (per-return End + catch-any; per-call and
per-loop handlers; sibling-loop two-phase plan; suspend/synthetic/bridge skip);
idempotency (transforms always run from pre-ASM task inputs, no double-instrumentation
within a build; okhttp dedup is runtime-side `addIfAbsent`; per-call wrapper skips its
own dispatcher; the removed `loadClassData` probe machinery is pinned removed by
`CallSiteInstrumentationProbeRemovalTest`); failure containment (every instrumenting
method visitor — including buffered `MethodNode` replay and AGP frame recomputation at
`visitMaxs` — is wrapped by `CatchingMethodVisitor`, which **re-throws with class+method
attribution**: a half-rewritten class cannot be silently emitted because the throw
fails the build); Jacoco (AGP orders its coverage transform after ASM instrumentation
on the same pipeline; our output is verifier-valid with recomputed frames — no shape
assumption for Jacoco to trip on; assessed from AGP task wiring, moderate confidence);
R8/minification of the *app* (injected `INVOKESTATIC`s are real references to `@Keep`
SDK classes, so R8 keeps them; `SdkSymbolAvailability` artifact-probes prevent
missing-class R8 failures against older SDKs; SDK self-obfuscation pins adapter FQNs).

Bytecode-verification coverage note (correcting an external claim): `CheckClassAdapter`
**is** used in this repo — `AsmTestHarness.verify()` runs it plus a class-loading
`SimpleVerifier` analysis, and the startup-tier, extensions-init, http_engine,
op-dispatch class-visitor and (new) thread tests call it. Gap: the okhttp tests and the
op-dispatch *injection* fixtures don't, because their hand-rolled probes reference
classes not on the test classpath and `SimpleVerifier` loads types; the harness needs a
non-loading `BasicVerifier` mode before those can opt in (§5). The compose compiler
plugin is covered by the stronger execute-the-output check in the matrix and the new
interop test.

---

## 4. Answers to the customer's two questions

### 4.1 Can `instrumentation.excludes` express "exclude only test-reachable classes"? — **No (confirmed).**

`InstrumentationExcludes` matches patterns against the **fully-qualified name of the
class currently being rewritten** (`ClassData.className`): exact FQN, package prefix,
or `*`-glob. Every ASM factory consults it in `isInstrumentable`. It knows nothing
about who calls the class or from where; "test-reachable" is not expressible. Excluding
`com.example.network.ClientFactory` removes Bugsee instrumentation from that class **in
the shipped APK too** — it is an escape hatch per class, not a per-consumer scope. Two
further limits: the Compose IR lanes (`compose`/`composeSecure`) do not consult
`excludes` at all, and (fixed today, §2.3) excluding the ExtensionsInit target used to
break extension loading.

### 4.2 Is there an AGP-supported way to instrument device builds but not the local unit-test classpath? — **No (verified against AGP 8.6.0). Alternatives below.**

Mechanics, from decompiled AGP 8.6.0 internals plus the customer's own diff:

- `TransformClassesWithAsmTask` is registered per component when any visitor is
  registered, and its output **replaces** the component's PROJECT-scope `CLASSES`
  ScopedArtifact (`TaskManager.maybeCreateTransformClassesWithAsmTask` →
  `ScopedArtifactsImpl.use(...).toTransform(CLASSES)`). There is **one** classes
  pipeline per component; dexing and everything else that reads the tested variant's
  final classes — including the unit-test task's classpath — see the same
  post-ASM output (`build/intermediates/classes/<variant>/transform<Variant>ClassesWithAsm`,
  exactly what the customer diffed).
- `AndroidUnitTest.CreationAction.computeClasspath` additionally routes the unit-test
  component's dependency JARs through
  `InstrumentationCreationConfig.getDependenciesClassesJarsPostInstrumentation(ALL)`.
- `variant.unitTest` (AGP's `UnitTest` extends `Component`) does own an
  `instrumentation` handle — but it can only **add** transforms for the unit-test
  component's own classes/dependency jars. It cannot un-instrument the tested
  variant's classes, which arrive already final. There is no per-consumer artifact
  fork, no "raw classes for host tests" flag, in AGP 8.6.

So within one variant, "instrumented APK + raw unit-test classpath" is not achievable
with supported AGP API. Honest ranking of what a consumer can do today:

1. **Make the injected runtime inert off-device (the durable fix — SDK-side).** This
   is the direction already taken: SDK `e99d7cd22` hardens the mainThreadMisuse and
   thread entry points; the okhttp/WS paths pass through when unconfigured. Once
   released, issues 1-class failures disappear without any build changes. Residual:
   the okhttp lane still *inserts* an interceptor (tests asserting client internals
   must tolerate it or use exclusion).
2. **Disable per feature or globally for test-only invocations:**
   `./gradlew testDebugUnitTest -Pbugsee.instrumentation.enabled=false` (or per-lane,
   e.g. `-Pbugsee.instrumentation.okhttp=false`). Works today; the classes are simply
   built uninstrumented for that invocation. Caveats: (a) it flips the whole variant's
   output — don't mix with `assemble` in the same invocation you ship (after §2.3's
   fix this no longer silently kills extensions, but you'd ship uninstrumented
   capture); (b) it invalidates the transform between test and assemble runs (rebuild
   cost).
3. **Keep instrumentation off `debug` and on `release`** — most JUnit runs use debug.
   Today this needs the property in (2) wired via CI; the plugin has no per-build-type
   DSL (see recommendation §5.1).
4. **`excludes` for the handful of app classes a test suite exercises hard** —
   accepting the device-side capture loss on those classes.

---

## 5. Recommendations

1. **Add per-build-type / per-variant instrumentation scoping (recommended, small).**
   The registrar already runs inside `onVariants`; a DSL like
   `bugsee { instrumentation { disableForBuildTypes.add("debug") } }` (or a
   `variantFilter`-style predicate) resolved per variant would give consumers the
   "release-only instrumentation" answer natively, which is the closest supportable
   approximation to "not in my unit tests". This is the concrete "scoped opt-out
   mechanism" this audit endorses; a true unit-test-only opt-out is not implementable
   on AGP's artifact model (§4.2) and should not be promised.
2. **Ship the SDK release containing `e99d7cd22` promptly** and state in the changelog
   that injected entry points are now unit-test-safe; that is the real fix for issue 1
   and the residual risk of issue 3.
3. **Fix the op-dispatch exception path** via `MethodNode` buffering + prepended
   narrow catch-any (design in §2.4), or add SDK-side orphan finalization; until then
   APM span data from throwing guarded calls is wrong-by-omission.
4. **Add a `BugseeSdkVersion` floor to ExtensionsInit** (§3.6) so old-SDK pairings keep
   manifest providers instead of stripping without compensation.
5. **Test-infra:** add a non-loading verify mode (CheckClassAdapter + `BasicVerifier`)
   to `AsmTestHarness` so the okhttp/op-dispatch injection fixtures can run bytecode
   verification despite absent runtime classes.
6. **Compose:** decide whether `isApplicable` should skip test compilations (§3.2);
   and if faithful cross-line default-lowering repros are wanted, budget per-line
   simulator builds in the variant matrix (the k21 interop test's simulator is the
   template).
7. **R8-minified dependency skip:** open an investigation item (§3.8) — AGP's visitor
   API can't read the `~~R8` marker directly; evaluate the `visitSource`-heuristic or
   an artifact-transform pre-scan before promising parity with Sentry's guard, and
   document `excludes` as the current workaround for a misbehaving minified AAR.
8. **Docs:** document that the Gradle-property key for HttpEngine is
   `bugsee.instrumentation.http_engine` (snake_case), that `ktor`/`cronet` involve no
   bytecode injection, and the §4 unit-test guidance verbatim in the plugin README.

---

## Changes made in this audit (all uncommitted, alongside pre-existing unrelated WIP)

| File | Change |
|---|---|
| `compose-compiler-plugin/src/legacyIr/.../BugseeComposeIrExtension.kt` | Guard: `isNullConstant` → `isDefaultedOrNullArgument` (composite/DEFAULT_VALUE aware) |
| `compose-compiler-plugin/src/modernIr/.../BugseeComposeIrExtension.kt` | Same |
| `compose-compiler-plugin/src/test/.../ComposeDefaultLoweringSimulator.kt` | NEW — byte-exact Compose default-lowering simulator plugin (test-only) |
| `compose-compiler-plugin/src/test/.../DefaultValueLoweringInteropTest.kt` | NEW — end-to-end repro; fails with the exact 4.0.5 crash on the old guard |
| `compose-compiler-plugin/src/variantTest/.../ComposeVariantMatrixTest.kt` | Corrected proxy-shape comment (IrConst fixture ≠ real lowering shape) |
| `src/main/.../instrumentation/thread/ThreadClassVisitor.kt` | Synthetic `run()` only for direct HandlerThread subclasses (VerifyError fix) |
| `src/test/.../instrumentation/thread/ThreadClassVisitorTest.kt` | NEW — incl. classloader-level VerifyError proof |
| `src/main/.../BugseePlugin.kt` | Strip gate resolved early and passed to manifest task (surgical edits around unrelated WIP) |
| `src/main/.../manifest/ExtensionStripGate.kt` | NEW — single strip/injection gate |
| `src/test/.../manifest/ExtensionStripGateTest.kt` | NEW — pins gate semantics |

Test evidence: root `:test` suite green; `:compose-compiler-plugin:test` green;
`:compose-compiler-plugin:composeVariantMatrix` green on all variants (k21/k22/k24
jars rebuilt from patched sources, timestamps verified); each fix shown to fail its
test in the pre-fix state (guard revert, unconditional-synthetic-run simulation,
gate truth table).
