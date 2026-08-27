package io.github.glandais.karoo.weather.karoo

import android.util.Log
import android.widget.RemoteViews
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter

/**
 * Guarded emission to a Karoo host.
 *
 * `IHandler.onNext(in Bundle)` is NOT declared `oneway` in the SDK's AIDL, so both [Emitter.onNext]
 * and [ViewEmitter.updateView] are SYNCHRONOUS Binder transactions. When the host process that
 * opened the view dies without a `stopView` reaching us — the ride app being killed while a field
 * is on screen — the next call throws `DeadObjectException`. Unchecked in Kotlin, uncaught inside a
 * `launch {}` with no handler, that takes the whole extension process down: every other field, the
 * map layer and the rain alerter with it.
 *
 * Both helpers return false instead. The caller's contract is to stop that one view's loop: a dead
 * binder fails identically on every subsequent tick, so retrying only hammers it.
 */
fun <T> Emitter<T>.safeNext(value: T): Boolean =
    runCatching { onNext(value) }
        .onFailure { Log.w(LOG_TAG, "emitter is gone, stopping this view", it) }
        .isSuccess

/** [safeNext] for the view channel. */
fun ViewEmitter.safeUpdate(views: RemoteViews): Boolean =
    runCatching { updateView(views) }
        .onFailure { Log.w(LOG_TAG, "view emitter is gone, stopping this view", it) }
        .isSuccess

private const val LOG_TAG = "karoo-weather"
