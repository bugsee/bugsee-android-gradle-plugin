plugins {
    id("com.android.fused-library")
    id("com.bugsee.android.gradle")
}
group = project.findProperty("bugseeFixtureGroup")?.toString() ?: "com.customer.fused"
androidFusedLibrary { namespace = "com.example.fused"; minSdk = 24 }
bugsee { buildInfo { enabled.set(false) } }
