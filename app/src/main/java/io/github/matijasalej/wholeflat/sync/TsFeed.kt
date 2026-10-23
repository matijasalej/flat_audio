package io.github.matijasalej.wholeflat.sync

/** What the stream server needs from the live transport stream of the TV (see `KitchenStreams`): the ring to read from and how to start. */
interface TsFeed {
    /** The last seconds of the stream as it was written. */
    val history: TsHistory

    /**
     * How old the place a new connection starts at is to be at least (ns): the stream was started so in the device test that the TV took
     * well (the newest IDR picture that is a second old). 0: at the next IDR picture made after the connection (see [requestSync]).
     */
    fun leadNanos(): Long

    /** Asks the encoder for an IDR picture as soon as it can. */
    fun requestSync()
}
