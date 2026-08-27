package org.aerialpod.core

/**
 * Wall-clock seconds since the epoch.
 *
 * Every replicated record is stamped with this and resolved last-writer-wins,
 * so it has to mean the same thing here as it does on the desktop: UTC seconds,
 * not device uptime and not milliseconds.
 */
expect fun epochSeconds(): Long

expect fun epochMillis(): Long
