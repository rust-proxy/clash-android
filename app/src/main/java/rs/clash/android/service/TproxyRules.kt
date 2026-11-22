package rs.clash.android.service

import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

/** The one-shot KernelSU manager applies rules and journals their inverses. */
internal class TproxyRules(
	dir: File,
) {
	private val undo = ArrayDeque<List<String>>()
	var installed = false
		private set

	// The root-owned journal survives both the controller and the one-shot manager.
	private val file = AtomicFile(File(dir, "rules.json"))
	private val boot = File("/proc/sys/kernel/random/boot_id").readText().trim()

	init {
		if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
			val saved = JSONObject(file.readFully().decodeToString())
			if (saved.getString("boot") == boot) {
				installed = saved.optBoolean("installed")
				val entries = saved.getJSONArray("undo")
				for (i in 0 until entries.length()) {
					val args = entries.getJSONArray(i)
					undo.addLast((0 until args.length()).map(args::getString))
				}
			} else {
				file.delete()
			}
		}
	}

	private fun save() {
		if (undo.isEmpty()) {
			file.delete()
			return
		}
		val data = JSONObject().put("boot", boot).put("installed", installed).put("undo", JSONArray(undo))
		val out = file.startWrite()
		try {
			out.write(data.toString().toByteArray())
			file.finishWrite(out)
		} catch (e: Throwable) {
			file.failWrite(out)
			throw e
		}
	}

	private class CommandError(
		val output: String,
		argv: List<String>,
	) : Exception("${argv.joinToString(" ")}: ${output.trim()}") {
		val missing get() =
			listOf(
				"Bad rule (does a matching rule exist",
				"No chain/target/match by that name",
				"No such file or directory",
				"No such process",
			).any(output::contains)
	}

	private fun command(argv: List<String>): String {
		val process = ProcessBuilder(argv).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().use { it.readText() }
		if (process.waitFor() != 0) throw CommandError(output, argv)
		return output
	}

	private fun change(
		apply: List<String>,
		revert: List<String>,
	) {
		// Write ahead so the next manager can undo an interrupted install.
		undo.addFirst(revert)
		save()
		try {
			command(apply)
		} catch (e: Exception) {
			undo.removeFirst()
			save()
			throw e
		}
	}

	fun install(
		config: JSONObject,
		uid: Int,
	) {
		installFamily(false, config, uid)
		if (config.getBoolean("ipv6")) installFamily(true, config, uid)
		installed = true
		save()
	}

	private fun installFamily(
		v6: Boolean,
		config: JSONObject,
		uid: Int,
	) {
		val ip = listOf("/system/bin/ip", if (v6) "-6" else "-4")
		val xt = listOf(if (v6) "/system/bin/ip6tables" else "/system/bin/iptables", "-w", "5", "-t", "mangle")
		val out = "CLASHRS_OUT"
		val pre = "CLASHRS_PRE"
		val table = "17893"
		val pref = "9055"
		val mark = "0x40000000/0x40000000"
		val loopback = if (v6) "::1" else "127.0.0.1"

		fun rule(
			chain: String,
			vararg args: String,
		) {
			val tail = listOf(chain) + args
			change(xt + "-A" + tail, xt + "-D" + tail)
		}
		check(
			command(ip + listOf("rule", "show")).lineSequence().none {
				it.startsWith("$pref:") || it.contains("lookup $table")
			},
		) { "Policy routing priority or table already in use" }
		val existingRoutes = command(ip + listOf("route", "show", "table", "all"))
		check(!Regex("""\btable $table\b""").containsMatchIn(existingRoutes)) { "Routing table already in use" }
		val existingChains = command(xt + "-S").lineSequence().toSet()
		check("-N $out" !in existingChains && "-N $pre" !in existingChains) { "TPROXY chains already in use" }
		for (chain in listOf(out, pre)) {
			change(xt + listOf("-N", chain), xt + listOf("-X", chain))
		}
		val localRoute = listOf("local", "default", "dev", "lo", "table", table)
		change(ip + listOf("route", "add") + localRoute, ip + listOf("route", "del") + localRoute)
		val policy = listOf("pref", pref, "fwmark", mark, "lookup", table)
		change(ip + listOf("rule", "add") + policy, ip + listOf("rule", "del") + policy)
		rule(out, "-m", "owner", "--uid-owner", uid.toString(), "-j", "RETURN")
		rule(out, "-m", "owner", "--gid-owner", uid.toString(), "-j", "RETURN")
		rule(out, "-o", "lo", "-j", "RETURN")
		val bypass =
			if (v6) {
				listOf(
					"::1/128",
					"ff00::/8",
				)
			} else {
				listOf("127.0.0.0/8", "224.0.0.0/4", "255.255.255.255/32")
			}
		for (prefix in bypass) rule(out, "-d", prefix, "-j", "RETURN")
		val systemDns = config.getBoolean("systemDns")
		if (systemDns) {
			// netd may send DNS as UID 0, including to a link-local resolver.
			for (proto in listOf("udp", "tcp")) {
				rule(out, "-p", proto, "--dport", "53", "-j", "MARK", "--set-xmark", mark)
			}
			rule(pre, "-p", "tcp", "--dport", "53", "-j", "TPROXY", "--on-ip", loopback, "--on-port", TproxySocket.TCP_DNS_PORT.toString())
		}
		rule(out, "-m", "owner", "--uid-owner", "0", "-j", "RETURN")
		rule(out, "-d", if (v6) "fe80::/10" else "169.254.0.0/16", "-j", "RETURN")
		rule(out, "-j", "MARK", "--set-xmark", mark)
		for (proto in listOf("tcp", "udp")) {
			rule(pre, "-p", proto, "-j", "TPROXY", "--on-ip", loopback, "--on-port", TproxySocket.PORT.toString())
		}
		// Android enables tcp_fwmark_accept. Once policy routing has delivered the
		// packet locally, prevent its SYN-ACK from inheriting our interception bit.
		rule("INPUT", "-i", "lo", "-m", "mark", "--mark", mark, "-j", "MARK", "--set-xmark", "0/0x40000000")
		rule("PREROUTING", "-i", "lo", "-m", "mark", "--mark", mark, "-j", pre)
		// Attach OUTPUT last and detach it first so no new traffic enters during cleanup.
		for (proto in listOf("tcp", "udp")) {
			rule("OUTPUT", "-p", proto, "-j", out)
		}
	}

	fun remove() {
		installed = false
		save()
		val failed = mutableListOf<List<String>>()
		while (undo.isNotEmpty()) {
			val argv = undo.removeFirst()
			try {
				command(argv)
			} catch (e: Exception) {
				if (e !is CommandError || !e.missing) {
					Log.e("ClashTproxy", "Could not remove session rule", e)
					failed.add(argv)
				}
			}
		}
		undo.addAll(failed)
		save()
		check(failed.isEmpty()) { "Incomplete TPROXY cleanup" }
	}
}
