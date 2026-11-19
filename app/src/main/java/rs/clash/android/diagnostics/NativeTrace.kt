package rs.clash.android.diagnostics

import com.google.protobuf.CodedInputStream
import java.io.InputStream

/** Reads only the crashing thread's app frames from the AOSP debuggerd tombstone.proto. */
internal data class NativeTrace(
	val signal: Int,
	val threadId: Int,
	val frames: List<String>,
) {
	companion object {
		fun read(
			stream: InputStream,
			apkPaths: Set<String>,
		): NativeTrace {
			val input = CodedInputStream.newInstance(stream)
			var tid = 0
			var signal = 0
			val threads = mutableMapOf<Int, List<String>>()
			input.fields { tag ->
				when (tag) {
					48 -> tid = readUInt32()
					82 ->
						message {
							fields { if (it == 8) signal = readInt32() else skipField(it) }
						}
					130, 202 -> message { readThreadEntry(threads, apkPaths) } // threads / guest_threads
					else -> skipField(tag)
				}
			}
			return NativeTrace(signal, tid, threads[tid].orEmpty())
		}
	}
}

private fun CodedInputStream.readThreadEntry(
	threads: MutableMap<Int, List<String>>,
	apkPaths: Set<String>,
) {
	var tid = 0
	val frames = mutableListOf<String>()
	fields { tag ->
		when (tag) {
			8 -> tid = readUInt32()
			18 ->
				message {
					fields { field ->
						if (field == 34 && frames.size < 12) {
							message { readAppFrame(apkPaths)?.let(frames::add) }
						} else {
							skipField(field)
						}
					}
				}
			else -> skipField(tag)
		}
	}
	if (frames.isNotEmpty()) threads[tid] = frames
}

private fun CodedInputStream.readAppFrame(apkPaths: Set<String>): String? {
	var pc = 0L
	var library = ""
	var buildId = ""
	fields {
		when (it) {
			8 -> pc = readUInt64()
			50 -> library = readString()
			66 -> buildId = readString()
			else -> skipField(it)
		}
	}
	val id = buildId.takeIf { it.length in 8..128 && it.all { c -> c in "0123456789abcdefABCDEF" } } ?: "unknown"
	// When mapped directly from the APK, debuggerd may omit the ELF name. Its build ID
	// still identifies the exact library; never send the private installation path.
	val module =
		when {
			library.substringAfterLast('/').substringAfterLast('!') == "libclash_android_ffi.so" -> "libclash_android_ffi.so"
			library in apkPaths && id != "unknown" -> "apk-embedded"
			else -> return null
		}
	return "$module $id 0x${pc.toULong().toString(16)}"
}

private inline fun CodedInputStream.fields(consume: CodedInputStream.(Int) -> Unit) {
	while (true) {
		val tag = readTag()
		if (tag == 0) return
		consume(tag)
	}
}

private inline fun CodedInputStream.message(consume: CodedInputStream.() -> Unit) {
	val limit = pushLimit(readRawVarint32())
	consume()
	popLimit(limit)
}
