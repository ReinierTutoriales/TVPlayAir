package com.tvplayair.receiver.session

/** Resolves the negotiated RAOP key from the ALAC element header. */
internal class RaopAlacKeySelector(
    private val candidates: List<ByteArray>,
    private val iv: ByteArray
) {
    var selectedIndex: Int? = null
        private set

    fun decrypt(payload: ByteArray, offset: Int, length: Int): ByteArray? {
        val selected = selectedIndex
        if (selected != null) {
            return SessionCrypto.decryptRaopAudioCbc(candidates[selected], iv, payload, offset, length)
                .takeIf(::hasAudioElementHeader)
        }

        for ((index, key) in candidates.withIndex()) {
            val plain = SessionCrypto.decryptRaopAudioCbc(key, iv, payload, offset, length)
            if (hasAudioElementHeader(plain)) {
                selectedIndex = index
                return plain
            }
        }
        return null
    }

    private fun hasAudioElementHeader(packet: ByteArray): Boolean =
        packet.size >= 16 &&
            // SCE or CPE tag, 4-bit element instance, then 12 reserved zero bits.
            (packet[0].toInt() and 0xC1) == 0 &&
            packet[1].toInt() == 0 &&
            (packet[2].toInt() and 0xE0) == 0
}
