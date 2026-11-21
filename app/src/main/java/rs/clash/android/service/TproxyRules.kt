package rs.clash.android.service

import android.util.Log
import org.json.JSONObject
import java.util.ArrayDeque

/** Only the authorized supervisor runs these commands, with numeric UID operands. */
internal class TproxyRules {
	private val undo = ArrayDeque<List<String>>()

	private fun command(argv: List<String>): String {
		val process = ProcessBuilder(argv).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().use { it.readText() }
		check(process.waitFor() == 0) { "${argv.joinToString(" ")}: ${output.trim()}" }
		return output
	}

	private fun change(
		apply: List<String>,
		revert: List<String>,
	) {
		command(apply)
		undo.addFirst(revert)
	}

	fun install(
		config: JSONObject,
		uid: Int,
	) {
		installFamily(false, config, uid)
		if (config.getBoolean("ipv6")) installFamily(true, config, uid)
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
		for (chain in listOf(out, pre)) {
			change(xt + listOf("-N", chain), xt + listOf("-X", chain))
		}
		val localRoute = listOf("local", "default", "dev", "lo", "table", table)
		change(ip + listOf("route", "add") + localRoute, ip + listOf("route", "del") + localRoute)
		val policy = listOf("pref", pref, "fwmark", mark, "lookup", table)
		change(ip + listOf("rule", "add") + policy, ip + listOf("rule", "del") + policy)
		rule(out, "-m", "owner", "--uid-owner", uid.toString(), "-j", "RETURN")
		rule(out, "-m", "owner", "--uid-owner", "0", "-j", "RETURN")
		rule(out, "-o", "lo", "-j", "RETURN")
		val bypass =
			if (v6) {
				listOf(
					"::1/128",
					"fe80::/10",
					"ff00::/8",
				)
			} else {
				listOf("127.0.0.0/8", "169.254.0.0/16", "224.0.0.0/4", "255.255.255.255/32")
			}
		for (prefix in bypass) rule(out, "-d", prefix, "-j", "RETURN")
		val uids = config.getJSONArray("uids")
		val mode = config.getString("mode")
		for (index in 0 until uids.length()) {
			val owner = uids.getInt(index).toString()
			if (mode == "ALLOWED") {
				rule(out, "-m", "owner", "--uid-owner", owner, "-j", "MARK", "--set-xmark", mark)
			} else if (mode == "DISALLOWED") {
				rule(out, "-m", "owner", "--uid-owner", owner, "-j", "RETURN")
			}
		}
		if (mode != "ALLOWED") rule(out, "-j", "MARK", "--set-xmark", mark)
		for (proto in listOf("tcp", "udp")) {
			rule(pre, "-p", proto, "-j", "TPROXY", "--on-ip", loopback, "--on-port", TproxyCore.PORT.toString())
		}
		// Android enables tcp_fwmark_accept. Once policy routing has delivered the
		// packet locally, prevent its SYN-ACK from inheriting our interception bit.
		rule("INPUT", "-i", "lo", "-m", "mark", "--mark", mark, "-j", "MARK", "--set-xmark", "0/0x40000000")
		rule("PREROUTING", "-i", "lo", "-m", "mark", "--mark", mark, "-j", pre)
		// Attach OUTPUT last and detach it first so no new traffic enters during cleanup.
		val firstUid = uid / 100000 * 100000
		for (proto in listOf("tcp", "udp")) {
			rule("OUTPUT", "-p", proto, "-m", "owner", "--uid-owner", "$firstUid-${firstUid + 99999}", "-j", out)
		}
	}

	fun remove() {
		var failed = false
		while (undo.isNotEmpty()) {
			val argv = undo.removeFirst()
			try {
				command(argv)
			} catch (e: Exception) {
				Log.e("ClashTproxy", "Could not remove session rule", e)
				failed = true
			}
		}
		check(!failed) { "Incomplete TPROXY cleanup" }
	}
}
