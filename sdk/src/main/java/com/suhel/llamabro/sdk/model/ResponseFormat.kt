package com.suhel.llamabro.sdk.model

sealed interface ResponseFormat {
    data object Text: ResponseFormat
    data class Json(val schema: String? = null): ResponseFormat
}