package com.bugsee.library;

/**
 * Stand-in for the SDK class the ExtensionsInit lane rewrites, with the empty
 * hook the real SDK ships. Compiled in only with -PbugseeFixtureInitProvider=hook.
 */
public class BugseeInitProvider {
    void initializeExtensions() {
    }
}
