package com.bugsee.fake

import android.app.Application

/**
 * Subject under test for the package denylist short-circuit. This class is
 * an `Application` subclass — which would normally make it an
 * `APPLICATION`-kind candidate — but its package starts with `com.bugsee.`
 * so the plugin's `PACKAGE_DENYLIST` rejects it in `isInstrumentable`.
 *
 * Expected: ZERO dispatcher INVOKESTATIC calls in this class's bytecode
 * at every tier (including FULL).
 */
class NotMyClass : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
