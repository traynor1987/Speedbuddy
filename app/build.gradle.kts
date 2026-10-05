plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "uk.co.traynor.speedbuddy"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "uk.co.traynor.speedbuddy"
        minSdk = 28
        targetSdk = 35
        versionCode = 15
        versionName = "0.2.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
// Public provenance only. CI supplies the exact checkout SHA; local development is explicit.
val ownerSourceSha = providers.environmentVariable("SPEED_BUDDY_SOURCE_SHA").orElse("development")
val ownerAssets = layout.buildDirectory.dir("generated/owner-assets")
val generateOwnerBuildIdentity = tasks.register("generateOwnerBuildIdentity") {
    inputs.property("sourceSha", ownerSourceSha)
    inputs.property("versionCode", 15)
    inputs.property("versionName", "0.2.6"
    outputs.dir(ownerAssets)
    doLast {
        val sha = ownerSourceSha.get()
        require(sha == "development" || sha.matches(Regex("[0-9a-f]{40}")))
        val folder = ownerAssets.get().asFile.apply { mkdirs() }
        folder.resolve("owner-build.json").writeText("""{"sourceSha":"$sha","versionCode":15,"versionName":"0.2.6"}""")
    }
}
android.sourceSets.getByName("main").assets.srcDir(ownerAssets)
tasks.named("preBuild").configure { dependsOn(generateOwnerBuildIdentity) }
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    // Passive Android Auto surface. It only observes the existing DriveBus; it
    // must never become another GPS, database or warning-service owner.
    implementation("androidx.car.app:app:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
