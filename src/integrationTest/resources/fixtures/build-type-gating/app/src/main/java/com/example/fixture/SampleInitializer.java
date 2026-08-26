package com.example.fixture;

import android.content.Context;

import androidx.startup.Initializer;

import java.util.Collections;
import java.util.List;

/**
 * Subject under test for the INITIALIZER kind. The plugin should wrap the
 * {@code create()} method with a method span at {@code STANDARD+} tiers.
 * At {@code MINIMAL} the Initializer kind is intentionally excluded
 * (only Application + ContentProvider qualify); at {@code OFF} no kind
 * is wrapped.
 *
 * Written in Java (not Kotlin) on purpose: Kotlin's compilation of
 * {@code Initializer<Unit>} emits two `create` methods — a `Unit`-typed
 * specific method and a synthetic bridge that returns `Object`. The
 * plugin's filter matches the bridge by descriptor
 * {@code (Landroid/content/Context;)Ljava/lang/Object;}, but the visitor
 * correctly skips synthetic/bridge methods (wrapping them would
 * double-count when the runtime call goes through the bridge). Net
 * effect: no wrap lands on Kotlin Initializer<Unit>.
 *
 * Writing the initializer in Java with a concrete {@code Object} return
 * type (or a non-Unit T such as {@code Initializer<String>}) produces a
 * single non-synthetic {@code create} method that the plugin wraps as
 * expected.
 */
public class SampleInitializer implements Initializer<Object> {

    @Override
    public Object create(Context context) {
        return new Object();
    }

    @Override
    public List<Class<? extends Initializer<?>>> dependencies() {
        return Collections.emptyList();
    }
}
