package io.eksoda.buildlogic

import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/**
 * The `eksoda.testdata` system property for the tests: this repository's testdata/ (receipt
 * fixtures). The directory is a task input by its relative paths only, so the test results stay
 * cacheable from a checkout in another place (a plain systemProperty would put the absolute
 * path into the cache key).
 */
class TestDataArgument(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val directory: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Deksoda.testdata=${directory.absolutePath}")
}

/** Gives this test task the repository's testdata/ directory ([TestDataArgument]). */
fun Test.useTestData(directory: File) {
    jvmArgumentProviders += TestDataArgument(directory)
}
