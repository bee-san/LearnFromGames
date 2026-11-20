// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** What a failed download says to the person: the network's own messages ("Unable to resolve host…") say nothing to them. */
fun downloadProblem(e: Exception, otherwise: String): String = when (e) {
    is UnknownHostException, is ConnectException -> "No connection. Check the network and try again."
    is SocketTimeoutException -> "The connection was too slow. Try again, on Wi-Fi if you can."
    is SSLException -> "The connection was interrupted. Try again."
    is IOException -> if (e.message.isNullOrBlank()) otherwise else "$otherwise ${e.message}"
    else -> e.message ?: otherwise
}
