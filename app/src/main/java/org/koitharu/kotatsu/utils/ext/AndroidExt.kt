package org.koitharu.kotatsu.utils.ext

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

val Context.connectivityManager: ConnectivityManager
	get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

suspend fun ConnectivityManager.waitForNetwork(): Network {
	val request = NetworkRequest.Builder().build()
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
		// fast path
		activeNetwork?.let { return it }
	}
	return suspendCancellableCoroutine { cont ->
		val callback = object : ConnectivityManager.NetworkCallback() {
			override fun onAvailable(network: Network) {
				unregisterNetworkCallback(this)
				if (cont.isActive) {
					cont.resume(network)
				}
			}
		}
		registerNetworkCallback(request, callback)
		cont.invokeOnCancellation {
			unregisterNetworkCallback(callback)
		}
	}
}

fun String.toUriOrNull() = if (isEmpty()) null else Uri.parse(this)

suspend fun CoroutineWorker.trySetForeground(): Boolean = runCatching {
	val info = getForegroundInfo()
	setForeground(info)
}.isSuccess