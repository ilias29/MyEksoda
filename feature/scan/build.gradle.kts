import java.net.URI
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

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

// Fetch only public language data at build time, never user photos.
@DisableCachingByDefault(because = "Downloads pinned upstream assets")
abstract class PrepareReceiptOcrModels : DefaultTask() {
    @get:Input
    abstract val revision: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val target = outputDirectory.get().asFile.resolve("tessdata")
        check(target.isDirectory || target.mkdirs())
        for (name in listOf("ell.traineddata", "eng.traineddata", "LICENSE")) {
            val url = URI("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/${revision.get()}/$name")
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
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val task = tasks.register<PrepareReceiptOcrModels>(
            "prepare${variant.name.replaceFirstChar { it.uppercaseChar() }}ReceiptOcrModels",
        ) {
            revision.set("65727574dfcd264acbb0c3e07860e4e9e9b22185")
        }
        checkNotNull(variant.sources.assets).addGeneratedSourceDirectory(task, PrepareReceiptOcrModels::outputDirectory)
    }
}
