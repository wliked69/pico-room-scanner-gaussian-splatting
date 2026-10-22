package com.example.roomsplat.domain.usecase

class CanExportUseCase {
    operator fun invoke(frameCount: Int): Boolean = frameCount >= 3
}
