package com.bugsee.library.contracts.performance;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stub mirror of the real SDK annotation used by the plugin's TestKit
 * integration tests. Annotation descriptor MUST match the real SDK's
 * {@code Lcom/bugsee/library/contracts/performance/BugseeTrace;} — the
 * plugin's FULL-tier visitor matches this exact descriptor when scanning
 * for annotation pickup. Any drift surfaces here as a compile error in
 * the fixture project.
 *
 * <p>{@link RetentionPolicy#CLASS} is intentional and must match the real
 * annotation: lower retention (SOURCE) would strip the annotation before
 * the plugin sees it; higher (RUNTIME) would needlessly retain it in the
 * dex output.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface BugseeTrace {
}
