package com.alex193a.rootmypixel.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.io.File

/**
 * User-visible execution settings, plus the facts that only stay true for the
 * current boot.
 */
object AppPreferences {
    private const val PREFS = "root_my_pixel"
    private const val KEY_SHIZUKU_MODE = "shizuku_mode"
    private const val KEY_BOOT_TOKEN = "boot_token"
    private const val KEY_ASHMEM_PATH = "boot_ashmem_path"
    private const val KEY_KASLR_BASE = "boot_kaslr_base"

    /** Tolerance for boot-token drift; elapsedRealtime and wall clock diverge slowly. */
    private const val BOOT_TOKEN_SLACK_MS = 5_000L

    /**
     * Whether the payload runs through Shizuku (uid 2000) rather than in the app's own
     * domain. Off by default — the app-domain path needs no external service.
     */
    fun shizukuMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHIZUKU_MODE, false)

    fun setShizukuMode(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SHIZUKU_MODE, enabled)
            .apply()
    }

    /**
     * Approximate wall-clock time of boot. Stable within a boot, different after
     * one, so it scopes cached values to the boot that produced them.
     */
    private fun bootToken(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun currentBootPrefs(context: Context): SharedPreferences? {
        val prefs = prefs(context)
        val stored = prefs.getLong(KEY_BOOT_TOKEN, Long.MIN_VALUE)
        val drift = bootToken() - stored
        return if (drift in -BOOT_TOKEN_SLACK_MS..BOOT_TOKEN_SLACK_MS) prefs else null
    }

    private fun editForCurrentBoot(context: Context): SharedPreferences.Editor {
        val editor = prefs(context).edit()
        if (currentBootPrefs(context) == null) {
            // New boot: drop everything the previous boot's kernel made true.
            editor.putLong(KEY_BOOT_TOKEN, bootToken())
                .remove(KEY_ASHMEM_PATH)
                .remove(KEY_KASLR_BASE)
        }
        return editor
    }

    /**
     * Path of the ashmem device node for this boot, resolved once and reused.
     *
     * The node is named `/dev/ashmem<boot_id>` and is the only one an app or a
     * shell may open — plain `/dev/ashmem` is labelled `ashmem_device`, which
     * neither domain is granted, and nothing outside a few HAL domains may list
     * `/dev` to find it another way. Resolving it early matters: a failed
     * exploit attempt leaves `/proc/sys/kernel/random/boot_id` reading kernel
     * memory until the next reboot, so a later read would build a bogus name.
     */
    fun ashmemPath(context: Context): String? {
        currentBootPrefs(context)?.getString(KEY_ASHMEM_PATH, null)?.let { return it }

        val bootId = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        }.getOrNull()
        if (bootId.isNullOrEmpty() || !BOOT_ID_PATTERN.matches(bootId)) return null

        val path = "/dev/ashmem$bootId"
        if (!File(path).exists()) return null

        editForCurrentBoot(context).putString(KEY_ASHMEM_PATH, path).apply()
        return path
    }

    /**
     * KASLR base observed by a previous attempt this boot, as the payload's
     * `KASLR_BASE` expects it. Handing it back lets a retry skip the slide,
     * which is the only stage that panics the device when it loses its race.
     */
    fun kaslrBase(context: Context): String? =
        currentBootPrefs(context)?.getString(KEY_KASLR_BASE, null)

    fun setKaslrBase(context: Context, base: String) {
        editForCurrentBoot(context).putString(KEY_KASLR_BASE, base).apply()
    }

    private val BOOT_ID_PATTERN =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
