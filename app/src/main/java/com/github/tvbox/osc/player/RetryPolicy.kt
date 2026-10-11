package com.github.tvbox.osc.player

object RetryPolicy {

    private const val MANIFEST_MALFORMED = "ERROR_CODE_PARSING_MANIFEST_MALFORMED"

    fun shouldRefreshResolve(errorCodeName: String?): Boolean =
        errorCodeName == MANIFEST_MALFORMED
}
