package org.koitharu.kotatsu.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

class CompositeMutexTest {

	@Test
	fun singleLock() = runTest {
		val mutex = CompositeMutex<Int>()
		mutex.lock(1)
		mutex.lock(2)
		mutex.unlock(1)
		assert(mutex.size == 1)
		mutex.unlock(2)
		assert(mutex.isEmpty())
	}

	@Test
	fun doubleLock() = runTest {
		val mutex = CompositeMutex<Int>()
		repeat(2) {
			launch(Dispatchers.Default) {
				mutex.lock(1)
			}
		}
		yield()
		mutex.unlock(1)
		val tryLock = withTimeoutOrNull(1000) {
			mutex.lock(1)
		}
		assertNull(tryLock)
	}

	@Test
	fun cancellation() = runTest {
		val mutex = CompositeMutex<Int>()
		mutex.lock(1)
		val job = launch {
			try {
				mutex.lock(1)
			} finally {
				mutex.unlock(1)
			}
		}
		withTimeout(2000) {
			job.cancelAndJoin()
		}
	}
}