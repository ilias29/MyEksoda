package io.eksoda.feature.scan

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.eksoda.core.data.repository.ReviewedReceiptInput
import io.eksoda.core.data.repository.ReviewedReceiptSaver
import javax.inject.Inject

@HiltViewModel
class ReviewedReceiptViewModel @Inject constructor(private val saver: ReviewedReceiptSaver) : ViewModel() {
    suspend fun save(input: ReviewedReceiptInput) = saver.save(input)
}
