plugins {
    id("com.android.lint")
    id("com.bugsee.android.gradle")
}
bugsee { buildInfo { enabled.set(false) } }
