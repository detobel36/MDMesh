package com.mdmesh.core.telemetry

import android.content.Context
import com.mdmesh.proto.TelemetryEventDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Drain/restore contract for buffered events, so the Android-free sync logic (and tests) don't
 *  depend on the persistent store. Implemented by [EventLog]. */
interface EventSink {
    fun record(type: String, detail: String? = null)
    fun drain(): List<TelemetryEventDto>
    fun restore(events: List<TelemetryEventDto>)
    fun peekRecent(limit: Int = 10): List<TelemetryEventDto> = emptyList()
}

/**
 * Persistent buffer of device lifecycle events. SharedPreferences-backed so events survive offline
 * AND process restart (unlike the in-memory command-ack buffer). Capped to avoid unbounded growth.
 * Lightweight enough to construct directly from a BroadcastReceiver (no Hilt) as well as via DI.
 */
@Singleton
class EventLog @Inject constructor(@ApplicationContext context: Context) : EventSink {
    private val prefs = context.getSharedPreferences("mdm_events", Context.MODE_PRIVATE)

    @Synchronized
    override fun record(type: String, detail: String?) {
        val event = TelemetryEventDto(type, System.currentTimeMillis(), detail)

        val list = load(KEY).toMutableList()
        list.add(event)
        save(KEY, cap(list, CAP))

        val history = load(KEY_HISTORY).toMutableList()
        history.add(event)
        save(KEY_HISTORY, cap(history, HISTORY_CAP))
    }

    @Synchronized
    override fun drain(): List<TelemetryEventDto> {
        val l = load(KEY)
        prefs.edit().remove(KEY).apply()
        return l
    }

    @Synchronized
    override fun restore(events: List<TelemetryEventDto>) {
        save(KEY, cap(events + load(KEY), CAP))
    }

    @Synchronized
    override fun peekRecent(limit: Int): List<TelemetryEventDto> {
        val l = load(KEY_HISTORY)
        return if (l.size <= limit) l else l.takeLast(limit)
    }

    private fun load(key: String): List<TelemetryEventDto> = decode(prefs.getString(key, null))
    private fun save(key: String, list: List<TelemetryEventDto>) {
        prefs.edit().putString(key, encode(list)).apply()
    }

    companion object {
        private const val KEY = "events"
        private const val KEY_HISTORY = "events_history"
        private const val CAP = 500
        private const val HISTORY_CAP = 100
        private val json = Json { ignoreUnknownKeys = true }

        /** Keep the most recent [max] events. */
        fun cap(list: List<TelemetryEventDto>, max: Int = CAP): List<TelemetryEventDto> =
            if (list.size <= max) list else list.takeLast(max)

        fun encode(list: List<TelemetryEventDto>): String =
            json.encodeToString(ListSerializer(TelemetryEventDto.serializer()), list)

        fun decode(s: String?): List<TelemetryEventDto> =
            if (s.isNullOrBlank()) emptyList()
            else runCatching {
                json.decodeFromString(ListSerializer(TelemetryEventDto.serializer()), s)
            }.getOrDefault(emptyList())
    }
}
