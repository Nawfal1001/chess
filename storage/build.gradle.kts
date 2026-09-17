plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.naoufel.decentralchess.storage"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":chess-core"))
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
}
