package com.terraworld.common.dto

import java.time.LocalDateTime

data class ErrorResponse(
    val code: String,
    val message: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
)
