package com.bugsee.android.gradle.instrumentation.operation_dispatch

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.fixtures.JavaSourceCompiler
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pin the FileInputStream / FileOutputStream type-remapping rewrite
 * in [OperationDispatchClassVisitor].
 *
 * The remap is correct only at `new FileInputStream(...)` allocation
 * sites — JVMS §4.10.1.9 limits the verifier-legal target of an
 * `INVOKESPECIAL <init>` instruction to either the type produced by
 * the preceding `NEW` or the immediate superclass of the current
 * class (the `super-call` case in a `<init>` body). A naïve
 * unconditional owner-rewrite of every `INVOKESPECIAL <init>` would
 * produce ART `VerifyError` at class load in user apps that subclass
 * `FileInputStream` / `FileOutputStream` (a common pattern in audio
 * buffering, file telemetry, etc.).
 *
 * Tests:
 *  - happy path: plain `new FileInputStream(f)` is remapped end-to-end.
 *  - super-call path: a user subclass's `super(f)` is NOT remapped.
 *  - mixed path: a user subclass that ALSO does `new FileInputStream(f)`
 *    inside its constructor — `super-call` stays, the `new` is
 *    remapped, both in the SAME method body.
 *
 * Note on verification: [AsmTestHarness.verify] uses ASM's
 * `SimpleVerifier`, which reflectively loads referenced classes via
 * the test classloader. The Bugsee wrapper classes
 * (`BugseeFileInputStream`, `BugseeFileOutputStream`) live in the
 * runtime SDK and are NOT on the gradle-plugin's test classpath, so
 * we apply [AsmTestHarness.verify] only to the super-call tests
 * (whose post-transform bytecode references real
 * `java/io/FileInputStream` only). The remap-producing tests rely on
 * instruction-list inspection — a mis-remap would surface as the
 * wrong owner appearing in the expected position.
 */
class OperationDispatchClassVisitorTest {

    private fun transformOpDispatch(bytes: ByteArray): ByteArray =
        AsmTestHarness.transform(bytes) { writer ->
            OperationDispatchClassVisitor(writer, className = "fixtures.Test")
        }

    private fun methodInsns(bytes: ByteArray, methodName: String): List<Any> {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, 0)
        val m = cn.methods.first { it.name == methodName } as MethodNode
        return m.instructions.toList()
    }

    private fun newOwners(bytes: ByteArray, methodName: String): List<String> =
        methodInsns(bytes, methodName)
            .filterIsInstance<TypeInsnNode>()
            .filter { it.opcode == org.objectweb.asm.Opcodes.NEW }
            .map { it.desc }

    private fun initOwners(bytes: ByteArray, methodName: String): List<String> =
        methodInsns(bytes, methodName)
            .filterIsInstance<MethodInsnNode>()
            .filter { it.name == "<init>" }
            .map { it.owner }

    @Test
    fun `plain new FileInputStream is remapped end-to-end`() {
        val source = """
            import java.io.File;
            import java.io.FileInputStream;
            import java.io.IOException;
            public class Allocator {
                public static void open(File f) throws IOException {
                    FileInputStream fis = new FileInputStream(f);
                    fis.close();
                }
            }
        """.trimIndent()

        val compiled = JavaSourceCompiler.compile("Allocator.java", source)
        val transformed = transformOpDispatch(compiled.getValue("Allocator"))
        // No AsmTestHarness.verify() here — the transformed class
        // references BugseeFileInputStream, which lives in the
        // runtime SDK and is not on the test classpath.

        assertEquals(
            listOf("com/bugsee/library/adapters/BugseeFileInputStream"),
            newOwners(transformed, "open"),
            "NEW must be remapped to the Bugsee wrapper",
        )
        assertEquals(
            listOf("com/bugsee/library/adapters/BugseeFileInputStream"),
            initOwners(transformed, "open"),
            "Paired <init> must be remapped to the Bugsee wrapper",
        )
    }

    @Test
    fun `super-call in a FileInputStream subclass is NOT remapped`() {
        // The user class extends FileInputStream and calls super(f).
        // JVMS §4.10.1.9: the INVOKESPECIAL <init> target must be the
        // current class's immediate superclass (FileInputStream), NOT
        // some unrelated wrapper. A rewrite to BugseeFileInputStream
        // produces a class that fails ART verification at load time.
        val source = """
            import java.io.File;
            import java.io.FileInputStream;
            import java.io.IOException;
            public class MyFis extends FileInputStream {
                public MyFis(File f) throws IOException {
                    super(f);
                }
            }
        """.trimIndent()

        val compiled = JavaSourceCompiler.compile("MyFis.java", source)
        val transformed = transformOpDispatch(compiled.getValue("MyFis"))

        AsmTestHarness.verify(transformed).assertOk()

        assertTrue(
            newOwners(transformed, "<init>").isEmpty(),
            "super-call has no paired NEW; the constructor must not emit a NEW",
        )
        assertEquals(
            listOf("java/io/FileInputStream"),
            initOwners(transformed, "<init>"),
            "super-call owner must stay as the real superclass — remapping it would produce a verifier-illegal INVOKESPECIAL",
        )
    }

    @Test
    fun `super-call in a FileOutputStream subclass is NOT remapped`() {
        val source = """
            import java.io.File;
            import java.io.FileOutputStream;
            import java.io.IOException;
            public class MyFos extends FileOutputStream {
                public MyFos(File f) throws IOException {
                    super(f);
                }
            }
        """.trimIndent()

        val compiled = JavaSourceCompiler.compile("MyFos.java", source)
        val transformed = transformOpDispatch(compiled.getValue("MyFos"))

        AsmTestHarness.verify(transformed).assertOk()

        assertEquals(
            listOf("java/io/FileOutputStream"),
            initOwners(transformed, "<init>"),
            "super-call in FileOutputStream subclass must not be remapped",
        )
    }

    @Test
    fun `subclass that also news a FileInputStream — super stays, new is remapped`() {
        // The hard case: BOTH a `super-call` call AND a `new
        // FileInputStream(...)` inside the same constructor body.
        // The NEW→<init> pairing tracker in the visitor must
        // distinguish the two: super-call has no paired NEW (skip
        // remap); the new-allocation has a paired NEW (remap both
        // sides). Java emits the bytecode roughly as:
        //   ALOAD 0
        //   ALOAD 1
        //   INVOKESPECIAL java/io/FileInputStream.<init> (Ljava/io/File;)V   ← super
        //   ALOAD 0
        //   NEW java/io/FileInputStream
        //   DUP
        //   ALOAD 1
        //   INVOKESPECIAL java/io/FileInputStream.<init> (Ljava/io/File;)V   ← new
        //   PUTFIELD MyFis.backup : Ljava/io/FileInputStream;
        //   RETURN
        val source = """
            import java.io.File;
            import java.io.FileInputStream;
            import java.io.IOException;
            public class MyFis extends FileInputStream {
                private FileInputStream backup;
                public MyFis(File f) throws IOException {
                    super(f);
                    this.backup = new FileInputStream(f);
                }
            }
        """.trimIndent()

        val compiled = JavaSourceCompiler.compile("MyFis.java", source)
        val transformed = transformOpDispatch(compiled.getValue("MyFis"))
        // verify() omitted — the post-transform bytecode references
        // BugseeFileInputStream, which lives in the runtime SDK and
        // is not on the gradle-plugin test classpath. Instruction-list
        // inspection is sufficient: a mis-remap of super(...) would
        // surface as the wrong owner in the assertion below.

        // Exactly one NEW, and it MUST be remapped.
        assertEquals(
            listOf("com/bugsee/library/adapters/BugseeFileInputStream"),
            newOwners(transformed, "<init>"),
        )

        // Exactly two <init> calls: the super-call (must stay as
        // java/io/FileInputStream) and the new-allocation (must be
        // remapped). Order is super-then-new because Java emits the
        // super call first.
        assertEquals(
            listOf(
                "java/io/FileInputStream",
                "com/bugsee/library/adapters/BugseeFileInputStream",
            ),
            initOwners(transformed, "<init>"),
            "super-call owner stays; the new-allocation's <init> is remapped",
        )
    }

    @Test
    fun `nested new FileInputStreams pop in LIFO order`() {
        // `new FileInputStream(new FileInputStream(f))` — outer NEW
        // pushed, inner NEW pushed, inner <init> pops the inner, outer
        // <init> pops the outer. Both must be remapped.
        val source = """
            import java.io.File;
            import java.io.FileInputStream;
            import java.io.IOException;
            public class Nest {
                public static void chain(File f) throws IOException {
                    FileInputStream fis = new FileInputStream(new FileInputStream(f).getFD());
                    fis.close();
                }
            }
        """.trimIndent()

        val compiled = JavaSourceCompiler.compile("Nest.java", source)
        val transformed = transformOpDispatch(compiled.getValue("Nest"))
        // verify() omitted — references BugseeFileInputStream.

        val news = newOwners(transformed, "chain")
        val inits = initOwners(transformed, "chain")

        assertTrue(
            news.size == 2 && news.all { it == "com/bugsee/library/adapters/BugseeFileInputStream" },
            "Both NEWs must be remapped (got $news)",
        )
        assertTrue(
            inits.count { it == "com/bugsee/library/adapters/BugseeFileInputStream" } == 2,
            "Both <init> calls paired with NEWs must be remapped (got $inits)",
        )
    }

    @Test
    fun `nested news of MISMATCHED types pop in LIFO order (FIS inside FOS)`() {
        // `new FileOutputStream(new FileInputStream(f).getFD())` —
        // stack push order: outer FOS, then inner FIS. LIFO pop order
        // must be inner-first: the inner <init> (a FileInputStream
        // constructor) consumes the inner pendingRemaps entry, leaving
        // only the outer FOS entry for the outer <init>. A FIFO
        // mutation (`removeFirst()` / `first()` in the matcher) would
        // try to pair the inner FIS <init> against the FRONT of the
        // deque — which is the outer FOS entry — and the type-equality
        // check would fail, leaving the inner <init> dispatched
        // through the regular operation-dispatch wrap (owner stays
        // `java/io/FileInputStream`, not remapped to
        // `BugseeFileInputStream`).
        //
        // This is the test that genuinely distinguishes LIFO from FIFO.
        // The same-types nested test above cannot, because both deque
        // entries are identical so `first() == last()`.
        val source = """
            import java.io.File;
            import java.io.FileInputStream;
            import java.io.FileOutputStream;
            import java.io.IOException;
            public class NestMixed {
                public static void chain(File f) throws IOException {
                    FileOutputStream fos = new FileOutputStream(new FileInputStream(f).getFD());
                    fos.close();
                }
            }
        """.trimIndent()

        val compiled = JavaSourceCompiler.compile("NestMixed.java", source)
        val transformed = transformOpDispatch(compiled.getValue("NestMixed"))
        // verify() omitted — references BugseeFileInputStream and BugseeFileOutputStream.

        val news = newOwners(transformed, "chain")
        val inits = initOwners(transformed, "chain")

        // Source-code order is: NEW FileOutputStream first (outer),
        // then NEW FileInputStream (inner argument-expression). javac
        // emits them in that order.
        assertEquals(
            listOf(
                "com/bugsee/library/adapters/BugseeFileOutputStream",
                "com/bugsee/library/adapters/BugseeFileInputStream",
            ),
            news,
            "Both NEWs must be remapped to their wrapper types in source order",
        )

        // Bytecode order of the <init> calls is reversed: the inner
        // FIS is constructed first (its `getFD()` is the arg to the
        // outer FOS constructor), then the outer FOS. So inits[0] is
        // the inner FIS init, inits[1] is the outer FOS init. Under
        // LIFO both are remapped; under FIFO, inits[0] would be the
        // raw `java/io/FileInputStream` because the matcher would
        // (incorrectly) consult the deque's front entry, which is the
        // outer FOS remap.
        assertEquals(
            listOf(
                "com/bugsee/library/adapters/BugseeFileInputStream",
                "com/bugsee/library/adapters/BugseeFileOutputStream",
            ),
            inits,
            "LIFO order: inner FIS <init> pairs with inner NEW; outer FOS <init> pairs with outer NEW",
        )
    }
}
