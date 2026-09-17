plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.naoufel.decentralchess.p2p"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":identity"))
    testImplementation("junit:junit:4.13.2")
}
