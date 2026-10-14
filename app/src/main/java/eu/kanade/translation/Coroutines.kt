package eu.kanade.translation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val translationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal fun CoroutineScope.launchIO(block: suspend CoroutineScope.() -> Unit): Job =
    launch(Dispatchers.IO, block = block)

internal fun launchIO(block: suspend CoroutineScope.() -> Unit): Job = translationScope.launchIO(block)
