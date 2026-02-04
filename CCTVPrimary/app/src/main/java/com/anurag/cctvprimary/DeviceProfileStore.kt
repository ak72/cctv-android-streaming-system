package com.anurag.cctvprimary

import android.content.Context
import android.os.Build
import android.util.Range
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed profile store (JSON payload).
 *
 * We key by:
 * - firmware fingerprint (re-probe on OTA)
 * - cameraId + lensFacing (re-probe if user switches camera)
 */
internal object DeviceProfileStore {
    private const val PREFS = "cctv_primary_device_profile_v1"
    private const val KEY_PREFIX = "profile_"

    private fun key(cameraId: String, lensFacing: Int): String {
        val fp = Build.FINGERPRINT ?: "unknown"
        return "$KEY_PREFIX$fp|$cameraId|$lensFacing"
    }

    fun load(context: Context, cameraId: String, lensFacing: Int): DeviceProfile? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(cameraId, lensFacing), null) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    fun save(context: Context, profile: DeviceProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(profile.cameraId, profile.lensFacing), encode(profile).toString())
            .apply()
    }

    private fun encodeSize(s: Size): JSONObject =
        JSONObject().put("w", s.width).put("h", s.height)

    private fun decodeSize(o: JSONObject): Size =
        Size(o.getInt("w"), o.getInt("h"))

    private fun encodeRange(r: Range<Int>?): JSONObject? {
        if (r == null) return null
        return JSONObject().put("l", r.lower).put("u", r.upper)
    }

    private fun decodeRange(o: JSONObject?): Range<Int>? {
        if (o == null) return null
        return Range(o.getInt("l"), o.getInt("u"))
    }

    private fun encodeConfigs(list: List<StreamConfig>): JSONArray {
        val arr = JSONArray()
        list.forEach { cfg ->
            arr.put(
                JSONObject()
                    .put("w", cfg.width)
                    .put("h", cfg.height)
                    .put("br", cfg.bitrate)
                    .put("fps", cfg.fps)
            )
        }
        return arr
    }

    private fun decodeConfigs(arr: JSONArray): List<StreamConfig> {
        val out = ArrayList<StreamConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(StreamConfig(o.getInt("w"), o.getInt("h"), o.getInt("br"), o.getInt("fps")))
        }
        return out
    }

    private fun encode(profile: DeviceProfile): JSONObject {
        val obj = JSONObject()
        obj.put("version", profile.version)
        obj.put("fingerprint", profile.fingerprint)
        obj.put("cameraId", profile.cameraId)
        obj.put("lensFacing", profile.lensFacing)
        obj.put("hardwareLevel", profile.hardwareLevel)
        obj.put("activeArraySize", profile.activeArraySize)
        obj.put("availableFixedFps", JSONArray(profile.availableFixedFps))
        obj.put("chosenFixedFps", profile.chosenFixedFps)
        obj.put("chosenAeFpsRange", encodeRange(profile.chosenAeFpsRange))
        obj.put("supportsConcurrentCameras", profile.supportsConcurrentCameras)
        obj.put("common4by3Size", encodeSize(profile.common4by3Size))
        obj.put(
            "common4by3Candidates",
            JSONArray().apply { profile.common4by3Candidates.forEach { put(encodeSize(it)) } }
        )
        obj.put("hasAvcSurfaceInput", profile.hasAvcSurfaceInput)
        obj.put("encoderBitrateMin", profile.encoderBitrateRangeBps?.first)
        obj.put("encoderBitrateMax", profile.encoderBitrateRangeBps?.last)
        obj.put("preferBufferMode", profile.preferBufferMode)
        obj.put("maxRecommendedBitrateBps", profile.maxRecommendedBitrateBps)
        obj.put("qualityLadder", encodeConfigs(profile.qualityLadder))
        obj.put("activeProbeSelectedConfig", profile.activeProbeSelectedConfig?.let { cfg ->
            JSONObject()
                .put("w", cfg.width)
                .put("h", cfg.height)
                .put("br", cfg.bitrate)
                .put("fps", cfg.fps)
        })
        obj.put("activeProbeSelectedPreferBufferMode", profile.activeProbeSelectedPreferBufferMode)
        obj.put("activeProbeVerifiedVideoCaptureCombo", profile.activeProbeVerifiedVideoCaptureCombo)
        return obj
    }

    private fun decode(raw: String): DeviceProfile {
        val o = JSONObject(raw)
        val min = if (o.has("encoderBitrateMin") && !o.isNull("encoderBitrateMin")) o.getInt("encoderBitrateMin") else null
        val max = if (o.has("encoderBitrateMax") && !o.isNull("encoderBitrateMax")) o.getInt("encoderBitrateMax") else null
        val brRange = if (min != null && max != null && max >= min) (min..max) else null

        val candArr = o.optJSONArray("common4by3Candidates") ?: JSONArray()
        val cands = ArrayList<Size>(candArr.length())
        for (i in 0 until candArr.length()) {
            cands.add(decodeSize(candArr.getJSONObject(i)))
        }

        return DeviceProfile(
            version = o.getInt("version"),
            fingerprint = o.getString("fingerprint"),
            cameraId = o.getString("cameraId"),
            lensFacing = o.getInt("lensFacing"),
            hardwareLevel = o.getInt("hardwareLevel"),
            activeArraySize = if (o.has("activeArraySize") && !o.isNull("activeArraySize")) {
                o.getString("activeArraySize")
            } else {
                null
            },
            availableFixedFps = run {
                val a = o.optJSONArray("availableFixedFps") ?: JSONArray()
                val list = ArrayList<Int>(a.length())
                for (i in 0 until a.length()) list.add(a.getInt(i))
                list
            },
            chosenFixedFps = o.getInt("chosenFixedFps"),
            chosenAeFpsRange = decodeRange(o.optJSONObject("chosenAeFpsRange")),
            supportsConcurrentCameras = o.optBoolean("supportsConcurrentCameras", false),
            common4by3Size = decodeSize(o.getJSONObject("common4by3Size")),
            common4by3Candidates = cands,
            hasAvcSurfaceInput = o.optBoolean("hasAvcSurfaceInput", true),
            encoderBitrateRangeBps = brRange,
            preferBufferMode = o.optBoolean("preferBufferMode", false),
            maxRecommendedBitrateBps = o.getInt("maxRecommendedBitrateBps"),
            qualityLadder = decodeConfigs(o.getJSONArray("qualityLadder")),
            activeProbeSelectedConfig = o.optJSONObject("activeProbeSelectedConfig")?.let { cfg ->
                StreamConfig(
                    cfg.getInt("w"),
                    cfg.getInt("h"),
                    cfg.getInt("br"),
                    cfg.getInt("fps")
                )
            },
            activeProbeSelectedPreferBufferMode = if (o.has("activeProbeSelectedPreferBufferMode") && !o.isNull("activeProbeSelectedPreferBufferMode")) {
                o.getBoolean("activeProbeSelectedPreferBufferMode")
            } else {
                null
            },
            activeProbeVerifiedVideoCaptureCombo = if (o.has("activeProbeVerifiedVideoCaptureCombo") && !o.isNull("activeProbeVerifiedVideoCaptureCombo")) {
                o.getBoolean("activeProbeVerifiedVideoCaptureCombo")
            } else {
                null
            }
        )
    }
}

