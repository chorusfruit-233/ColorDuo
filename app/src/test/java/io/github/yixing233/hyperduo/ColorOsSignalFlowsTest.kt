/* SPDX-License-Identifier: GPL-3.0-only */
package io.github.yixing233.hyperduo

import java.net.URLClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ColorOsSignalFlowsTest {
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(5)
    }

    @Test fun hiddenNativeViewKeepsReceivingSignalChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val modem = MutableStateFlow(4)
        val nativeIcon = modem.stateIn(scope, SharingStarted.WhileSubscribed(), 4)
        val warnings = mutableListOf<String>()
        val signals = ColorOsSignalFlows(warnings::add)
        try {
            // With no view collecting it, the OEM cache stays at its startup value.
            modem.value = 1
            assertEquals(4, nativeIcon.value)
            assertNull(signals.read(nativeIcon, null))
            signals.sync(listOf(nativeIcon))
            awaitCondition { signals.read(nativeIcon, null) == 1 }
            modem.value = 3
            awaitCondition { signals.read(nativeIcon, null) == 3 }
            modem.value = 0
            awaitCondition { signals.read(nativeIcon, null) == 0 }
            assertTrue(warnings.toString(), warnings.isEmpty())
        } finally {
            signals.clear()
            scope.cancel()
        }
    }

    @Test fun dualSimChangesAndRemovedSubscriptionsRemainIndependent() = runBlocking {
        val first = MutableStateFlow(4)
        val second = MutableStateFlow(1)
        val signals = ColorOsSignalFlows { fail(it) }
        try {
            repeat(20) { signals.sync(listOf(first, second, first)) }
            awaitCondition { first.subscriptionCount.value == 1 && second.subscriptionCount.value == 1 }
            first.value = 2
            second.value = 3
            assertEquals(2, signals.read(first, null))
            assertEquals(3, signals.read(second, null))
            signals.sync(listOf(second))
            awaitCondition { first.subscriptionCount.value == 0 }
            assertNull(signals.read(first, null))
            assertEquals(3, signals.read(second, null))
            signals.sync(emptyList<Any>())
            awaitCondition { second.subscriptionCount.value == 0 }
        } finally { signals.clear() }
    }

    @Test fun onlyCollectedModelsCanOverrideTheTelephonyFallback() = runBlocking {
        val wrapper = MutableStateFlow<Int?>(4)
        val cellular = MutableStateFlow<Int?>(2)
        val signals = ColorOsSignalFlows { fail(it) }
        try {
            signals.sync(listOf(cellular))
            awaitCondition { cellular.subscriptionCount.value == 1 }
            assertEquals(2, signals.read(wrapper, cellular))
            signals.sync(listOf(wrapper, cellular))
            awaitCondition { wrapper.subscriptionCount.value == 1 }
            assertEquals(4, signals.read(wrapper, cellular))
            wrapper.value = null
            assertEquals(2, signals.read(wrapper, cellular))
            signals.clear()
            assertNull(signals.read(wrapper, cellular))
        } finally { signals.clear() }
    }

    @Test fun reloadRollbackResumesAndDisableReleasesCollection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val modem = MutableStateFlow(4)
        val icon = modem.stateIn(scope, SharingStarted.WhileSubscribed(), 4)
        val signals = ColorOsSignalFlows { fail(it) }
        try {
            signals.sync(listOf(icon))
            awaitCondition { modem.subscriptionCount.value == 1 }
            signals.pause()
            awaitCondition { modem.subscriptionCount.value == 0 }
            modem.value = 1
            assertNull(signals.read(icon, null))
            signals.resume()
            awaitCondition { signals.read(icon, null) == 1 }
            signals.clear()
            awaitCondition { modem.subscriptionCount.value == 0 }
            assertNull(signals.read(icon, null))
            signals.sync(listOf(icon))
            awaitCondition { modem.subscriptionCount.value == 1 }
        } finally {
            signals.clear()
            scope.cancel()
        }
    }

    @Test fun unsupportedVendorFlowFallsBackWithoutRetryingEveryFrame() {
        val unsupported = Any()
        val warnings = mutableListOf<String>()
        val signals = ColorOsSignalFlows(warnings::add)
        try {
            repeat(20) { signals.sync(listOf(unsupported)) }
            assertNull(signals.read(unsupported, null))
            assertEquals(1, warnings.size)
        } finally { signals.clear() }
    }

    @Test fun nativeCoroutineTypesMayLiveInAnotherClassLoader() = runBlocking {
        val urls = arrayOf(
            requireNotNull(kotlin.Unit::class.java.protectionDomain?.codeSource).location,
            requireNotNull(CoroutineScope::class.java.protectionDomain?.codeSource).location,
        )
        URLClassLoader(urls, ClassLoader.getSystemClassLoader().parent).use { loader ->
            val factory = loader.loadClass("kotlinx.coroutines.flow.StateFlowKt")
                .getMethod("MutableStateFlow", Any::class.java)
            val nativeIcon = factory.invoke(null, 3)
            assertNotSame(MutableStateFlow::class.java.classLoader, nativeIcon.javaClass.classLoader)
            val signals = ColorOsSignalFlows { fail(it) }
            try {
                signals.sync(listOf(nativeIcon))
                awaitCondition { signals.read(nativeIcon, null) == 3 }
                val count = Refl.callByName(nativeIcon, "getSubscriptionCount")
                awaitCondition { Refl.callByName(count, "getValue") == 1 }
                signals.clear()
                awaitCondition { Refl.callByName(count, "getValue") == 0 }
            } finally { signals.clear() }
        }
    }
}
