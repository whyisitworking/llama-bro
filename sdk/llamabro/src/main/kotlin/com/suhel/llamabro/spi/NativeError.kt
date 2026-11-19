package com.suhel.llamabro.spi

import com.suhel.llamabro.InternalLlamaBroApi

/**
 * A failed native call: [status] is `lb_status`, [errorJson] is `{"error": "..."}`, with `"code"` set for a
 * missing file (`file_not_found`) or too little memory (`insufficient_memory`). Public under the opt-in marker so
 * that an [Engine] in `llamabro-testing` can fail a load as the native side does.
 */
@InternalLlamaBroApi
public class NativeError(public val status: Int, public val errorJson: String) : RuntimeException(errorJson) {
    public companion object {
        /** `LB_ERR_CANCELLED`: the load's progress callback said stop (D67). */
        public const val CANCELLED: Int = 6
    }
}
