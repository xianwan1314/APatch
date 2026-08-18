package me.bmax.apatch.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Base64
import me.bmax.apatch.apApp
import java.security.MessageDigest

private const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"
private const val APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a

@Suppress("DEPRECATION")
private fun signatureFromAPI(context: Context): ByteArray? {
    return try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
        }

        val signatures: Array<out Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                packageInfo.signatures
            }

        signatures?.firstOrNull()?.toByteArray()
    } catch (_: Exception) {
        null
    }
}

private fun ByteArray.uint32LE(offset: Int): Long {
    if (offset < 0 || offset + 4 > size) throw IndexOutOfBoundsException()
    return (this[offset].toLong() and 0xff) or
        ((this[offset + 1].toLong() and 0xff) shl 8) or
        ((this[offset + 2].toLong() and 0xff) shl 16) or
        ((this[offset + 3].toLong() and 0xff) shl 24)
}

private fun ByteArray.uint64LE(offset: Int): Long {
    if (offset < 0 || offset + 8 > size) throw IndexOutOfBoundsException()
    var value = 0L
    for (i in 0 until 8) {
        value = value or ((this[offset + i].toLong() and 0xff) shl (i * 8))
    }
    return value
}

private fun ByteArray.sliceBytes(offset: Int, length: Int): ByteArray {
    if (offset < 0 || length < 0 || offset + length > size) throw IndexOutOfBoundsException()
    return copyOfRange(offset, offset + length)
}

private fun findEocdOffset(apkBytes: ByteArray): Int {
    val minOffset = maxOf(0, apkBytes.size - 65557)
    for (offset in apkBytes.size - 22 downTo minOffset) {
        if (apkBytes.uint32LE(offset) == 0x06054b50L) return offset
    }
    return -1
}

private fun signatureFromAPK(context: Context): ByteArray? {
    return try {
        val apkBytes = java.io.File(context.packageResourcePath).readBytes()
        val eocdOffset = findEocdOffset(apkBytes)
        if (eocdOffset < 0) return null

        val centralDirOffset = apkBytes.uint32LE(eocdOffset + 16).toInt()
        val magicOffset = centralDirOffset - APK_SIG_BLOCK_MAGIC.length
        if (magicOffset < 0) return null
        val magic = String(apkBytes.sliceBytes(magicOffset, APK_SIG_BLOCK_MAGIC.length))
        if (magic != APK_SIG_BLOCK_MAGIC) return null

        val blockSize = apkBytes.uint64LE(centralDirOffset - 24)
        if (blockSize > Int.MAX_VALUE) return null
        val blockStart = centralDirOffset - blockSize.toInt() - 8
        if (blockStart < 0) return null

        var pairOffset = blockStart + 8
        val pairEnd = centralDirOffset - 24
        while (pairOffset < pairEnd) {
            val pairSize = apkBytes.uint64LE(pairOffset)
            if (pairSize < 4 || pairSize > Int.MAX_VALUE) return null

            val idOffset = pairOffset + 8
            val valueOffset = idOffset + 4
            val nextPairOffset = pairOffset + 8 + pairSize.toInt()
            if (nextPairOffset > pairEnd) return null

            val id = apkBytes.uint32LE(idOffset).toInt()
            if (id == APK_SIGNATURE_SCHEME_V2_BLOCK_ID) {
                val signersLength = apkBytes.uint32LE(valueOffset).toInt()
                if (signersLength <= 0 || valueOffset + 4 + signersLength > nextPairOffset) {
                    return null
                }

                val signerLengthOffset = valueOffset + 4
                val signerLength = apkBytes.uint32LE(signerLengthOffset).toInt()
                val signerOffset = signerLengthOffset + 4
                if (signerLength <= 0 || signerOffset + signerLength > nextPairOffset) return null

                val signedDataLength = apkBytes.uint32LE(signerOffset).toInt()
                val signedDataOffset = signerOffset + 4
                if (signedDataLength <= 0 || signedDataOffset + signedDataLength > signerOffset + signerLength) {
                    return null
                }

                val digestsLength = apkBytes.uint32LE(signedDataOffset).toInt()
                val certificatesOffset = signedDataOffset + 4 + digestsLength
                val certificatesLength = apkBytes.uint32LE(certificatesOffset).toInt()
                val certificateLengthOffset = certificatesOffset + 4
                val certificateLength = apkBytes.uint32LE(certificateLengthOffset).toInt()
                val certificateOffset = certificateLengthOffset + 4
                if (certificatesLength <= 0 ||
                    certificateOffset + certificateLength > certificatesOffset + 4 + certificatesLength
                ) {
                    return null
                }

                return apkBytes.sliceBytes(certificateOffset, certificateLength)
            }

            pairOffset = nextPairOffset
        }

        null
    } catch (_: Exception) {
        null
    }
}

private fun validateSignature(signatureBytes: ByteArray?, validSignature: String): Boolean {
    signatureBytes ?: return false
    val digest = MessageDigest.getInstance("SHA-256")
    val signatureHash = Base64.encodeToString(digest.digest(signatureBytes), Base64.NO_WRAP)
    return signatureHash == validSignature
}

fun verifyAppSignature(validSignature: String): Boolean {
    val context = apApp.applicationContext
    val apkSignature = signatureFromAPK(context)
    val apiSignature = signatureFromAPI(context)

    return validateSignature(apiSignature, validSignature) &&
        validateSignature(apkSignature, validSignature)
}
