package io.eksoda.core.data.imports

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.eksoda.core.data.imports.ImportHarness.Companion.MYMARKET
import io.eksoda.core.model.ImportSubmission
import io.eksoda.engine.InternalError
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The import worker's own cases, beyond the pipeline (ImportPipelineTest). */
@RunWith(AndroidJUnit4::class)
class ImportWorkerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var app: ImportHarness

    @Before
    fun setUp() {
        app = ImportHarness(folder.root)
        app.fetcher.serve(MYMARKET)
    }

    @After
    fun tearDown() {
        // A failed setUp keeps its own error.
        if (::app.isInitialized) app.close()
    }

    @Test
    fun `a job whose every attempt was cut short fails instead of running forever`() = runTest {
        val jobId = (app.service.submit(Fixtures.url(MYMARKET)) as ImportSubmission.Queued).jobId
        // The process was killed during each attempt: none recorded a result.
        app.database.openHelper.writableDatabase.execSQL(
            "UPDATE import_job SET attempt_count = 99 WHERE id = ?",
            arrayOf(jobId),
        )
        app.runWorker(jobId)
        val job = checkNotNull(app.jobs.get(jobId))
        assertThat(listOf(job.status, job.lastErrorCode))
            .isEqualTo(listOf("failed", InternalError().code.code))
        assertThat(app.count("SELECT COUNT(*) FROM receipt")).isEqualTo(0)
    }

    @Test
    fun `an expedited import below Android 12 shows the quiet progress notification`() = runTest {
        assertThat(app.worker("any-job").getForegroundInfo().notificationId).isEqualTo(1)
    }
}
