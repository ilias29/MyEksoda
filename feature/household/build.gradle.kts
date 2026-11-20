plugins {
    alias(libs.plugins.eksoda.android.feature)
}

dependencies {
    implementation(libs.androidx.paging.compose)
    // Joining a household: the camera reads the other phone's QR code (zxing-cpp, offline).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.cpp)
    // Adding a phone: the household code drawn as a QR code.
    implementation(libs.zxing.core)
}
