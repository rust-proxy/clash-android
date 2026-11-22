package rs.clash.traffictest

import android.net.DnsResolver
import android.net.InetAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.net.IDN
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun fakeIpV6Connect(
	domain: String,
	cidr: String,
): String {
	val prefix = cidr.substringBefore('/')
	val bits = cidr.substringAfter('/', "").toIntOrNull()
	val network = runCatching { InetAddresses.parseNumericAddress(prefix) }.getOrNull()
	if (network !is Inet6Address || bits == null || bits !in 0..128) throw ProbeError(R.string.invalid_prefix)
	val answers = dnsLookup(domain, DnsResolver.TYPE_AAAA)
	val expected = BigInteger(1, network.address).shiftRight(128 - bits)
	if (answers.any { it !is Inet6Address || BigInteger(1, it.address).shiftRight(128 - bits) != expected }) {
		throw ProbeError(R.string.prefix_mismatch, answers.joinToString { it.hostAddress.orEmpty() })
	}
	val address = answers.first()
	val host = IDN.toASCII(domain.trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
	return withContext(Dispatchers.IO) {
		suspendCancellableCoroutine { continuation ->
			val tcp = Socket()
			continuation.invokeOnCancellation { tcp.close() }
			val protocol =
				try {
					tcp.use {
						tcp.connect(InetSocketAddress(address, 443), 5000)
						tcp.soTimeout = 5000
						val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
						(factory.createSocket(tcp, host, 443, true) as SSLSocket).use { tls ->
							tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
							tls.startHandshake()
							tls.session.protocol
						}
					}
				} catch (e: Exception) {
					val reason = e.localizedMessage ?: e.javaClass.simpleName
					continuation.resumeWithException(ProbeError(R.string.connect_failed, address.hostAddress.orEmpty(), reason))
					return@suspendCancellableCoroutine
				}
			continuation.resume("$host → [${address.hostAddress}]:443 · $protocol")
		}
	}
}
