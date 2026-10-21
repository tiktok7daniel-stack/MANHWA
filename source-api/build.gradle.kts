plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.kanade.tachiyomi.source"

    defaultConfig {
        consumerProguardFiles("consumer-proguard.pro")
    }
}

dependencies {
    implementation(projects.core.common)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.injekt)
    implementation(libs.rxJava)
    implementation(libs.jsoup)
    // MANHWA anime: Aniyomi's anime source API (extensions run a small local server for some videos)
    api(libs.nanohttpd)

    implementation(libs.androidx.preference)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
}
