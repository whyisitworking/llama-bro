package com.suhel.llamabro.hub.spi

import com.suhel.llamabro.InternalLlamaBroApi
import java.io.File

/**
 * Space reservation and directory durability, provided by the platform: `AndroidStorage` in production. Public only for
 * `llamabro-hub-testing`, whose scripted disk reports the free space a test sets
 * ([D75](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d75)). Apps never implement it.
 */
@InternalLlamaBroApi
public interface Storage {
    /** Bytes the app may still write under [dir]. */
    public fun allocatableBytes(dir: File): Long

    /** Sets [bytes] aside under [dir] before a download; throws an `IOException` when it can't. */
    public fun allocate(dir: File, bytes: Long)

    /** Makes [dir]'s entries durable after a rename. */
    public fun fsyncDir(dir: File)
}
