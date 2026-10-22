package com.example.roomsplat.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanExportUseCaseTest {
    private val useCase = CanExportUseCase()
    @Test fun insufficientFramesCannotBeExported() { assertFalse(useCase(0)); assertFalse(useCase(2)) }
    @Test fun minimumAndLargerCapturesCanBeExported() { assertTrue(useCase(3)); assertTrue(useCase(200)) }
}
