package freedom.app.helper

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.Deflater
import java.util.zip.Inflater

// ═════════════════════════════════════════════════════════════════════════════
//  FreedomCrypto  —  single source of truth for all encryption in this app
// ═════════════════════════════════════════════════════════════════════════════
//
//  Pipeline (send):    plaintext  →  compress  →  flag byte  →  XOR-cyclic  →  Base64
//  Pipeline (receive): Base64     →  XOR-cyclic  →  flag byte  →  decompress  →  plaintext
//
//  Cyclic XOR model:  Every message is XOR'd with the full key starting at
//  byte 0.  The same key bytes are reused for every message until rotation.
//  No offset tracking, no state sync, no capacity limit (up to key size per message).
//
// ═════════════════════════════════════════════════════════════════════════════

object FreedomCrypto {

    /** Bootstrap key for QR exchange — 256 bytes keeps QR scannable at ERROR_CORRECT_L. */
    const val BOOTSTRAP_KEY_BYTES = 256

    /** Per-direction message key — 24 KB allows messages up to 24 KB before compression. */
    const val MESSAGE_KEY_BYTES = 24 * 1024

    /** Default messages before key rotation — balances security vs. UX interruption. */
    const val DEFAULT_ROTATION_THRESHOLD = 100

    /** Number of segments produced from one generation session. */
    const val KEY_SEGMENTS = 6

    /** Total bytes generated at once (144 KB), then split into [KEY_SEGMENTS] × [MESSAGE_KEY_BYTES]. */
    const val MASTER_PAD_BYTES = MESSAGE_KEY_BYTES * KEY_SEGMENTS

    /** First 32 hex chars of key shown as visual fingerprint for manual verification. */
    const val FINGERPRINT_LENGTH = 32

    // ── Magic headers ───────────────────────────────────────────────────────
    val MAGIC_BOOTSTRAP  = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x42, 0x53)  // FF FF 42 53 ("BS")
    val MAGIC_KEY_ROTATE = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x4B, 0x52)  // FF FF 4B 52 ("KR")

    // ── Bootstrap packet types ──────────────────────────────────────────────
    const val BS_KEY_CHUNK: Byte = 0x01
    const val BS_INFO:      Byte = 0x02
    const val BS_KEY_DONE:  Byte = 0x03
    const val BS_ACK:       Byte = 0x04

    // Internal flags stored as the first encrypted byte to signal compression
    private const val FLAG_UNCOMPRESSED: Byte = 0x00
    private const val FLAG_COMPRESSED:   Byte = 0x01

    // ══════════════════════════════════════════════════════════════════════════
    //  KEY GENERATION
    // ══════════════════════════════════════════════════════════════════════════

    /** Generate a bootstrap key (ephemeral, for QR). */
    fun generateBootstrapKey(): ByteArray {
        val key = ByteArray(BOOTSTRAP_KEY_BYTES)
        SecureRandom().nextBytes(key)
        return key
    }

    /** Generate a 24 KB per-direction message key. Returns raw bytes. */
    fun generateMessageKey(): ByteArray {
        val key = ByteArray(MESSAGE_KEY_BYTES)
        SecureRandom().nextBytes(key)
        return key
    }

    /**
     * Generate a new key pad of [padLengthBytes] random bytes with multi-source entropy.
     * Returns a Base64-encoded string ready to store.
     */
    suspend fun generateKey(
        context: Context,
        padLengthBytes: Int = MESSAGE_KEY_BYTES,
        extraEntropy: ByteArray = byteArrayOf()
    ): String {
        require(padLengthBytes > 0) { "padLengthBytes must be positive" }
        val pad = ByteArray(padLengthBytes)
        SecureRandom().nextBytes(pad)

        xorInto(pad, extraEntropy)
        xorInto(pad, withContext(Dispatchers.Main) { collectSensorBytes(context, 200L) })
        xorInto(pad, longToBytes(System.nanoTime()))
        xorInto(pad, longToBytes(SystemClock.elapsedRealtimeNanos()))
        xorInto(pad, longToBytes(System.currentTimeMillis()))
        xorInto(pad, batteryBytes(context))
        xorInto(pad, telephonyBytes(context))
        xorInto(pad, processBytes(context))

        return pad.toBase64()
    }

    /**
     * Collect raw sensor bytes over [durationMs] milliseconds.
     */
    suspend fun collectMotionEntropy(context: Context, durationMs: Long = 3000L): ByteArray =
        withContext(Dispatchers.Main) {
            coroutineScope {
                val sensorJob = async { collectSensorBytes(context, durationMs) }
                val micJob    = async(Dispatchers.IO) { collectMicrophoneBytes(durationMs) }
                sensorJob.await() + micJob.await()
            }
        }

    // ══════════════════════════════════════════════════════════════════════════
    //  CYCLIC XOR CORE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * XOR [data] with [key] starting at byte 0, cycling the key.
     * Used for both bootstrap and message encryption.
     */
    fun xorCyclic(data: ByteArray, key: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "Key must not be empty" }
        return ByteArray(data.size) { i ->
            (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ENCRYPT  (compress → flag byte → cyclic XOR → Base64)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Compress [plaintext], prepend a 1-byte compression flag, then XOR-cyclic
     * with [keyBytes]. Returns a Base64 string.
     *
     * No offset parameter. Encryption always starts at key byte 0.
     */
    fun encrypt(plaintext: ByteArray, keyBytes: ByteArray): String {
        val compressed = compress(plaintext)
        val (flag, payload) = if (compressed.size < plaintext.size) {
            FLAG_COMPRESSED to compressed
        } else {
            FLAG_UNCOMPRESSED to plaintext
        }

        val toEncrypt = byteArrayOf(flag) + payload
        val cipher = xorCyclic(toEncrypt, keyBytes)
        return cipher.toBase64()
    }

    /** Convenience overload accepting a String plaintext. */
    fun encrypt(plaintext: String, keyBytes: ByteArray): String =
        encrypt(plaintext.toByteArray(Charsets.UTF_8), keyBytes)

    // ══════════════════════════════════════════════════════════════════════════
    //  DECRYPT  (Base64 → cyclic XOR → strip flag → decompress)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Reverse of [encrypt]. Decrypt [ciphertextBase64] using [keyBytes],
     * then decompress if the original was compressed.
     */
    fun decrypt(ciphertextBase64: String, keyBytes: ByteArray): ByteArray {
        val cipherBytes = ciphertextBase64.fromBase64()
        val decrypted = xorCyclic(cipherBytes, keyBytes)

        val flag    = decrypted[0]
        val payload = decrypted.copyOfRange(1, decrypted.size)

        return when (flag) {
            FLAG_COMPRESSED -> decompress(payload)
            else            -> payload
        }
    }

    /** Convenience overload returning a String. */
    fun decryptToString(ciphertextBase64: String, keyBytes: ByteArray): String =
        String(decrypt(ciphertextBase64, keyBytes), Charsets.UTF_8)

    // ══════════════════════════════════════════════════════════════════════════
    //  UTILITY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * First [FINGERPRINT_LENGTH] chars of the Base64 key — a short identifier
     * for display purposes only.
     */
    fun keyFingerprint(keyBase64: String): String = keyBase64.take(FINGERPRINT_LENGTH)

    /**
     * Split a master pad into [segments] equal Base64-encoded chunks.
     */
    fun splitKey(masterKeyBase64: String, segments: Int = KEY_SEGMENTS): List<String> {
        val bytes = masterKeyBase64.fromBase64()
        require(bytes.size % segments == 0) {
            "Master pad size ${bytes.size} B is not divisible by $segments"
        }
        val chunkSize = bytes.size / segments
        return (0 until segments).map { i ->
            bytes.copyOfRange(i * chunkSize, (i + 1) * chunkSize).toBase64()
        }
    }

    /**
     * Shannon entropy of the raw pad bytes, in bits per byte.
     */
    fun entropyBitsPerByte(keyBase64: String): Double {
        val bytes = keyBase64.fromBase64()
        if (bytes.isEmpty()) return 0.0
        val freq = IntArray(256)
        for (b in bytes) freq[b.toInt() and 0xFF]++
        val n = bytes.size.toDouble()
        var h = 0.0
        for (count in freq) {
            if (count == 0) continue
            val p = count / n
            h -= p * (Math.log(p) / Math.log(2.0))
        }
        return h
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PASSCODE ENCRYPTION  (PBKDF2-SHA256 → AES-256-GCM)
    // ══════════════════════════════════════════════════════════════════════════

    /** PBKDF2 iterations — tuned for ~200 ms on mid-range Android devices (2024). */
    private const val PBKDF2_ITERATIONS = 200_000
    private const val PBKDF2_KEY_BITS   = 256
    private const val SALT_BYTES        = 16
    private const val GCM_IV_BYTES      = 12
    private const val GCM_TAG_BITS      = 128

    fun encryptWithPasscode(plaintext: String, passcode: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv   = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val key  = deriveAesKey(passcode, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return (salt + iv + ciphertext).toBase64()
    }

    fun decryptWithPasscode(encoded: String, passcode: String): String {
        val data       = encoded.fromBase64()
        val salt       = data.copyOfRange(0, SALT_BYTES)
        val iv         = data.copyOfRange(SALT_BYTES, SALT_BYTES + GCM_IV_BYTES)
        val ciphertext = data.copyOfRange(SALT_BYTES + GCM_IV_BYTES, data.size)
        val key        = deriveAesKey(passcode, salt)
        val cipher     = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun deriveAesKey(passcode: String, salt: ByteArray): SecretKeySpec {
        val spec    = PBEKeySpec(passcode.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COMPRESSION  (raw DEFLATE, no GZIP header)
    // ══════════════════════════════════════════════════════════════════════════

    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, /*nowrap=*/ true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun decompress(data: ByteArray): ByteArray {
        val inflater = Inflater(/*nowrap=*/ true)
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ENTROPY SOURCES  (private — only called by generateKey)
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun collectSensorBytes(context: Context, durationMs: Long): ByteArray {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val collectedBytes    = mutableListOf<Byte>()
        val registeredListeners = mutableListOf<SensorEventListener>()

        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GRAVITY
        ).forEach { type ->
            val sensor = sensorManager.getDefaultSensor(type) ?: return@forEach
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    for (v in event.values) {
                        val bits = java.lang.Float.floatToRawIntBits(v)
                        collectedBytes.add((bits          and 0xFF).toByte())
                        collectedBytes.add(((bits shr  8) and 0xFF).toByte())
                        collectedBytes.add(((bits shr 16) and 0xFF).toByte())
                        collectedBytes.add(((bits shr 24) and 0xFF).toByte())
                    }
                    val ts = event.timestamp
                    for (shift in 0..7) collectedBytes.add(((ts shr (shift * 8)) and 0xFF).toByte())
                }
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            registeredListeners.add(listener)
        }

        delay(durationMs)
        registeredListeners.forEach { sensorManager.unregisterListener(it) }
        return collectedBytes.toByteArray()
    }

    private fun collectMicrophoneBytes(durationMs: Long): ByteArray {
        return try {
            val sampleRate = 44100
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return byteArrayOf()
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return byteArrayOf()
            }
            recorder.startRecording()
            val target = (sampleRate * 2 * durationMs / 1000).toInt()  // * 2 because 16-bit PCM = 2 bytes per sample
            val buffer = ByteArray(target)
            var offset = 0
            val deadline = System.currentTimeMillis() + durationMs + 200L
            while (offset < buffer.size && System.currentTimeMillis() < deadline) {
                val n = recorder.read(buffer, offset, buffer.size - offset)
                if (n > 0) offset += n else break
            }
            recorder.stop()
            recorder.release()
            buffer.copyOf(offset)
        } catch (_: Exception) { byteArrayOf() }
    }

    private fun batteryBytes(context: Context): ByteArray = try {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        byteArrayOf(
            i?.getIntExtra(BatteryManager.EXTRA_LEVEL,       0)?.toByte() ?: 0,
            i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.toByte() ?: 0,
            i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE,     0)?.toByte() ?: 0,
            i?.getIntExtra(BatteryManager.EXTRA_PLUGGED,     0)?.toByte() ?: 0,
            i?.getIntExtra(BatteryManager.EXTRA_HEALTH,      0)?.toByte() ?: 0
        )
    } catch (_: Exception) { byteArrayOf() }

    private fun telephonyBytes(context: Context): ByteArray = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        (tm.networkOperatorName + tm.networkCountryIso).toByteArray(Charsets.UTF_8)
    } catch (_: Exception) { byteArrayOf() }

    private fun processBytes(context: Context): ByteArray {
        val out = mutableListOf<Byte>()
        out += longToBytes(Process.myPid().toLong()).toList()
        out += longToBytes(Thread.currentThread().id).toList()
        out += longToBytes(Runtime.getRuntime().freeMemory()).toList()
        out += longToBytes(Runtime.getRuntime().totalMemory()).toList()
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            out += longToBytes(mi.availMem).toList()
        } catch (_: Exception) {}
        return out.toByteArray()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /** XOR [source] bytes cyclically into [target] (wraps if source is shorter). */
    private fun xorInto(target: ByteArray, source: ByteArray) {
        if (source.isEmpty()) return
        for (i in target.indices)
            target[i] = (target[i].toInt() xor source[i % source.size].toInt()).toByte()
    }

    private fun longToBytes(v: Long): ByteArray =
        ByteArray(8) { i -> ((v shr (i * 8)) and 0xFF).toByte() }

    fun ByteArray.toBase64(): String  = Base64.encodeToString(this, Base64.NO_WRAP)
    fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
