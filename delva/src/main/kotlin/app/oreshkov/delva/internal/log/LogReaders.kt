package app.oreshkov.delva.internal.log

import app.oreshkov.delva.internal.io.FileIo
import java.nio.file.Path

/**
 * Positional readers of a store's log files, for code that replays them: the writer's own [LogFiles], or the handles
 * of a process that only reads the store.
 */
internal interface LogReaders {
    /** Where log file [fileNo] is: a control file, or a file of lane [laneId]. */
    fun pathOf(fileNo: Long, control: Boolean, laneId: Int): Path

    /** A handle for positional reads of log file [fileNo], which the MANIFEST or the log being replayed lists. */
    fun reader(fileNo: Long, control: Boolean, laneId: Int): FileIo
}

/** Handles for reading fragment records by lane file number, wherever the fragment index locates them. */
internal fun interface LaneReaders {
    /** The handle of lane file [fileNo], which a fragment-index entry of a pinned version locates. */
    fun laneReader(fileNo: Long): FileIo
}
