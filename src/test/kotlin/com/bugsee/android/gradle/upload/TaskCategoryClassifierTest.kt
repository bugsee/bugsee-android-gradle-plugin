package com.bugsee.android.gradle.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskCategoryClassifierTest {

    @Test fun `java-kotlin compile classifies as JAVA`() {
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":app:compileReleaseKotlin"))
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":app:compileReleaseJavaWithJavac"))
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":lib:kaptReleaseKotlin"))
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":lib:kspReleaseKotlin"))
    }

    @Test fun `desugar and dex tasks classify as JAVA`() {
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":app:desugarReleaseFileDependencies"))
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":app:dexBuilderRelease"))
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":app:mergeDexRelease"))
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":app:minifyReleaseWithR8"))
    }

    @Test fun `java resource merging classifies as JAVA not packaging`() {
        // `mergeReleaseJavaResource` contains "Resource" (singular, no
        // trailing s) — AGP uses this for non-code files shipped under
        // META-INF/ inside the jar. Attributed to JAVA to avoid
        // confusing users with a RESOURCES bucket inflated by jar
        // resources.
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":app:mergeReleaseJavaResource"))
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":app:packageReleaseJavaResource"))
    }

    @Test fun `native CMake and strip classify as NATIVE`() {
        assertEquals(TaskCategory.NATIVE,
            TaskCategoryClassifier.classify(":app:externalNativeBuildRelease"))
        assertEquals(TaskCategory.NATIVE,
            TaskCategoryClassifier.classify(":app:configureCMakeRelWithDebInfo"))
        assertEquals(TaskCategory.NATIVE,
            TaskCategoryClassifier.classify(":app:buildCMakeRelease"))
        assertEquals(TaskCategory.NATIVE,
            TaskCategoryClassifier.classify(":app:stripReleaseDebugSymbols"))
    }

    @Test fun `native debug symbol extraction classifies as NATIVE`() {
        assertEquals(TaskCategory.NATIVE,
            TaskCategoryClassifier.classify(":app:extractReleaseNativeDebugMetadata"))
        assertEquals(TaskCategory.NATIVE,
            TaskCategoryClassifier.classify(":app:extractReleaseNativeSymbolTables"))
    }

    @Test fun `unrelated extractNative-prefixed tasks stay in OTHER`() {
        // Regression guard: the NATIVE regex was briefly `extract...Native[A-Z].*`
        // (any suffix) — that would have captured any hypothetical
        // future `extractReleaseNativeAnnotations` etc. The suffix
        // alternative is now scoped to the three real AGP outputs.
        assertEquals(TaskCategory.OTHER,
            TaskCategoryClassifier.classify(":app:extractReleaseNativeAnnotations"))
    }

    @Test fun `manifest processing classifies as RESOURCES`() {
        assertEquals(TaskCategory.RESOURCES,
            TaskCategoryClassifier.classify(":app:processReleaseMainManifest"))
        assertEquals(TaskCategory.RESOURCES,
            TaskCategoryClassifier.classify(":app:processReleaseManifestForPackage"))
    }

    @Test fun `jni lib merging classifies as NATIVE`() {
        assertEquals(TaskCategory.NATIVE,
            TaskCategoryClassifier.classify(":app:mergeReleaseNativeLibs"))
        assertEquals(TaskCategory.NATIVE,
            TaskCategoryClassifier.classify(":app:mergeReleaseJniLibFolders"))
    }

    @Test fun `res-asset processing classifies as RESOURCES`() {
        assertEquals(TaskCategory.RESOURCES,
            TaskCategoryClassifier.classify(":app:mergeReleaseResources"))
        assertEquals(TaskCategory.RESOURCES,
            TaskCategoryClassifier.classify(":app:processReleaseResources"))
        assertEquals(TaskCategory.RESOURCES,
            TaskCategoryClassifier.classify(":app:mergeReleaseAssets"))
        assertEquals(TaskCategory.RESOURCES,
            TaskCategoryClassifier.classify(":app:generateReleaseResValues"))
        assertEquals(TaskCategory.RESOURCES,
            TaskCategoryClassifier.classify(":app:compileReleaseShaders"))
        assertEquals(TaskCategory.RESOURCES,
            TaskCategoryClassifier.classify(":app:crunchReleaseResources"))
    }

    @Test fun `package resources is RESOURCES not PACKAGING (precedence)`() {
        // This is the central precedence test: `packageReleaseResources`
        // matches BOTH the resources regex and the packaging catch-all.
        // The resources rule must win.
        assertEquals(TaskCategory.RESOURCES,
            TaskCategoryClassifier.classify(":app:packageReleaseResources"))
    }

    @Test fun `optimize-resources classifies as RESOURCES`() {
        assertEquals(TaskCategory.RESOURCES,
            TaskCategoryClassifier.classify(":app:optimizeReleaseResources"))
    }

    @Test fun `bundle-sign-zip classify as PACKAGING`() {
        assertEquals(TaskCategory.PACKAGING,
            TaskCategoryClassifier.classify(":app:bundleRelease"))
        assertEquals(TaskCategory.PACKAGING,
            TaskCategoryClassifier.classify(":app:packageRelease"))
        assertEquals(TaskCategory.PACKAGING,
            TaskCategoryClassifier.classify(":app:packageReleaseBundle"))
        assertEquals(TaskCategory.PACKAGING,
            TaskCategoryClassifier.classify(":app:signReleaseBundle"))
    }

    @Test fun `unknown tasks fall into OTHER`() {
        assertEquals(TaskCategory.OTHER,
            TaskCategoryClassifier.classify(":app:lintVitalRelease"))
        assertEquals(TaskCategory.OTHER,
            TaskCategoryClassifier.classify(":app:checkReleaseAarMetadata"))
        assertEquals(TaskCategory.OTHER,
            TaskCategoryClassifier.classify(":app:preBuild"))
        assertEquals(TaskCategory.OTHER,
            TaskCategoryClassifier.classify(":app:writeReleaseSigningConfigVersions"))
    }

    @Test fun `empty path segments fall into OTHER`() {
        assertEquals(TaskCategory.OTHER, TaskCategoryClassifier.classify(""))
        assertEquals(TaskCategory.OTHER, TaskCategoryClassifier.classify(":"))
        assertEquals(TaskCategory.OTHER, TaskCategoryClassifier.classify(":app:"))
    }

    @Test fun `multi-module nested path uses basename`() {
        assertEquals(TaskCategory.MANAGED_CODE,
            TaskCategoryClassifier.classify(":feature:payment:compileReleaseKotlin"))
    }
}
