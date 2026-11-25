import java.net.URI

plugins {
    alias(libs.plugins.eksoda.android.feature)
}

dependencies {
    implementation(project(":core:engine"))
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.paging.compose)
    // QR decoding on the device, with no network and no telemetry. Never ML Kit.
    implementation(libs.zxing.cpp)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}


// Fetch only public language data at build time, never user photos. Pin the upstream release commit.
val ocrAssets = layout.buildDirectory.dir("generated/receiptOcrAssets")
val prepareReceiptOcrModels = tasks.register("prepareReceiptOcrModels") {
    val revision = "65727574dfcd264acbb0c3e07860e4e9e9b22185"
    inputs.property("modelRevision", revision)
    outputs.dir(ocrAssets)
    doLast {
        val target = ocrAssets.get().asFile.resolve("tessdata")
        check(target.isDirectory || target.mkdirs())
        for (name in listOf("ell.traineddata", "eng.traineddata", "LICENSE")) {
            val url = URI("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$revision/$name")
            val connection = url.toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            val file = target.resolve(name)
            val temp = target.resolve("$name.part")
            try {
                connection.getInputStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                check(temp.length() > 0)
                check(temp.renameTo(file)) { "Could not install OCR asset $name" }
            } finally {
                temp.delete()
            }
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(ocrAssets)
tasks.named("preBuild").configure { dependsOn(prepareReceiptOcrModels) }
