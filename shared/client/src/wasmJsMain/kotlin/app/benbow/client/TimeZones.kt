@file:OptIn(ExperimentalWasmJsInterop::class)

package app.benbow.client

// kotlinx-datetime knows time zones by name in the browser only once js-joda's database is loaded (ADR-029): without
// it an imported event's TZID couldn't be read, and the device's zone would be a bare offset.

@JsModule("@js-joda/timezone")
private external object JsJodaTimeZoneModule : JsAny

/** Loads the time zone database; call once before any date is handled. */
fun installTimeZones() {
    JsJodaTimeZoneModule
}
