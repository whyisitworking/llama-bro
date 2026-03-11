package com.suhel.llamabro.sdk.schema

sealed interface ResponseFormat {
    data object Text: ResponseFormat
    data class Json(val schema: String? = null): ResponseFormat
}