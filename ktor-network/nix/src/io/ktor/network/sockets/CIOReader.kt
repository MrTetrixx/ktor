/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets

import io.ktor.network.selector.*
import io.ktor.utils.io.*
import io.ktor.utils.io.errors.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*

internal fun CoroutineScope.attachForReadingImpl(
    userChannel: ByteChannel,
    descriptor: Int,
    selectable: Selectable,
    selector: SelectorManager
): WriterJob = writer(Dispatchers.Unconfined, userChannel) {
    while (!channel.isClosedForWrite) {
        val count = channel.write { memory, startIndex, endIndex ->
            val bufferStart = memory.pointer + startIndex
            val size = endIndex - startIndex
            val bytesRead = recv(descriptor, bufferStart, size.convert(), 0).toInt()

            when (bytesRead) {
                0 -> channel.close()
                -1 -> {
                    if (errno == EAGAIN) {
                        return@write 0
                    }
                    throw PosixException.forErrno()
                }
            }

            bytesRead
        }

        if (count == 0 && !channel.isClosedForWrite) {
            selector.select(selectable, SelectInterest.READ)
        }

        channel.flush()
    }

    channel.closedCause?.let { throw it }
}.apply {
    invokeOnCompletion {
        shutdown(descriptor, SHUT_RD)
    }
}
