package rs.clash.android.diagnostics

import com.google.protobuf.CodedOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class NativeTraceTest {
	@Test
	fun keepsOnlyAppFramesFromCrashingThread() {
		val tombstone =
			message {
				writeByteArray(16, thread(32, frame(111, "/apex/libc.so"), frame(222, "/data/app/base.apk!libclash_android_ffi.so")))
				writeByteArray(16, thread(33, frame(333, "/other/libclash_android_ffi.so")))
				writeString(14, "sensitive abort message")
				writeUInt32(6, 32)
				writeByteArray(10, message { writeInt32(1, 6) })
			}
		assertEquals(NativeTrace(6, 32, listOf("libclash_android_ffi.so 1234abcd 0xde")), NativeTrace.read(tombstone.inputStream(), emptySet()))
	}

	@Test
	fun resolvesGuestFramesMappedDirectlyFromTheOriginalApk() {
		val apk = "/data/app/old-install/base.apk"
		val tombstone =
			message {
				writeUInt32(6, 7)
				writeByteArray(25, thread(7, frame(257, apk), frame(123, "/data/app/another/base.apk")))
			}
		assertEquals(listOf("apk-embedded 1234abcd 0x101"), NativeTrace.read(tombstone.inputStream(), setOf(apk)).frames)
	}

	@Test(expected = IOException::class)
	fun rejectsTruncatedFrame() {
		NativeTrace.read(byteArrayOf(82, 10, 8).inputStream(), emptySet())
	}

	private fun thread(
		tid: Int,
		vararg frames: ByteArray,
	) = message {
		writeUInt32(1, tid)
		writeByteArray(2, message { frames.forEach { writeByteArray(4, it) } })
	}

	private fun frame(
		pc: Long,
		file: String,
	) = message {
		writeUInt64(1, pc)
		writeString(6, file)
		writeString(8, "1234abcd")
	}

	private fun message(write: CodedOutputStream.() -> Unit): ByteArray {
		val bytes = ByteArrayOutputStream()
		CodedOutputStream.newInstance(bytes).apply(write).flush()
		return bytes.toByteArray()
	}
}
