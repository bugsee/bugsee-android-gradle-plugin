package com.bugsee.library.contracts.performance;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-only stand-in for the real {@code @BugseeTrace} annotation that
 * lives in the SDK repo at
 * {@code library/src/main/java/com/bugsee/library/contracts/performance/BugseeTrace.java}.
 *
 * <p>The plugin's test sources need an annotation type at this exact
 * FQN so {@link JavaSourceCompiler}-compiled fixtures can reference
 * {@code @BugseeTrace} via a normal {@code import} without dragging in
 * the SDK module as a test dependency. The plugin itself never
 * references this type (it identifies the annotation by its descriptor
 * string in bytecode, not via classpath resolution).
 *
 * <p>Retention is {@link RetentionPolicy#CLASS} to match the real
 * annotation — annotation references land in the class file's
 * {@code RuntimeInvisibleAnnotations} attribute, which ASM exposes as
 * {@link org.objectweb.asm.tree.MethodNode#invisibleAnnotations}. If
 * this were {@code RUNTIME} retention instead, ASM would expose the
 * annotation via {@code visibleAnnotations} and the transform's
 * detection check would miss it.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface BugseeTrace {
}
