package com.bugsee.android.gradle

import com.android.build.gradle.api.BaseVariant

interface AppTokenProvider {
    String getAppToken(BaseVariant variant)
}
