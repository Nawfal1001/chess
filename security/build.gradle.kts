plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.naoufel.decentralchess.security"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":chess-core"))
    implementation(project(":identity"))
    testImplementation("junit:junit:4.13.2")
}
