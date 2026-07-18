package com.macrophage.barspeed.witmotion

/**
 * WitMotion BLE protocol constants (WT901BLECL and siblings).
 *
 * These are the vendor's published UUIDs; the connection layer must verify them
 * against the live GATT table and fall back to matching by characteristic
 * properties if the vendor changes them.
 */
object WitmotionProtocol {
    const val SERVICE_UUID = "0000ffe5-0000-1000-8000-00805f9a34fb"
    const val NOTIFY_CHARACTERISTIC_UUID = "0000ffe4-0000-1000-8000-00805f9a34fb"
    const val WRITE_CHARACTERISTIC_UUID = "0000ffe9-0000-1000-8000-00805f9a34fb"

    const val FRAME_HEADER: Int = 0x55
    const val FLAG_MEASUREMENT: Int = 0x61
    const val FLAG_REGISTER: Int = 0x71
    const val FRAME_LENGTH = 20

    /** Full-scale ranges for the default WT901BLECL configuration. */
    const val ACC_FULL_SCALE_G = 16.0
    const val GYRO_FULL_SCALE_DPS = 2000.0
    const val ANGLE_FULL_SCALE_DEG = 180.0

    // Registers (subset used by the app).
    const val REG_SAVE = 0x00
    const val REG_RATE = 0x03
    const val REG_BATTERY = 0x64

    /** Values for REG_RATE. */
    enum class OutputRate(val registerValue: Int, val hz: Double) {
        RATE_10_HZ(0x06, 10.0),
        RATE_20_HZ(0x07, 20.0),
        RATE_50_HZ(0x08, 50.0),
        RATE_100_HZ(0x09, 100.0),
        RATE_200_HZ(0x0B, 200.0),
    }
}

/** Builders for the FF AA command frames written to the ffe9 characteristic. */
object WitmotionCommands {
    private fun command(register: Int, valueL: Int, valueH: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xAA.toByte(), register.toByte(), valueL.toByte(), valueH.toByte())

    /** Set the streaming output rate. Follow with [save] to persist across power cycles. */
    fun setOutputRate(rate: WitmotionProtocol.OutputRate): ByteArray =
        command(WitmotionProtocol.REG_RATE, rate.registerValue, 0x00)

    /** Request a one-shot read of [register]; the reply arrives as a 0x71 frame. */
    fun readRegister(register: Int): ByteArray = command(0x27, register, 0x00)

    fun save(): ByteArray = command(WitmotionProtocol.REG_SAVE, 0x00, 0x00)
}
