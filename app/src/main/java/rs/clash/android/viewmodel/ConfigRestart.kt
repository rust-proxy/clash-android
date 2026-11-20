package rs.clash.android.viewmodel

import android.content.Context
import kotlinx.coroutines.CancellationException
import rs.clash.android.R
import rs.clash.android.data.AppData
import rs.clash.android.service.VpnStartResult

internal suspend fun restartVpnForConfigChange(context: Context): String? =
	try {
		when (AppData.vpn.restart()) {
			VpnStartResult.Started -> null
			VpnStartResult.MissingProfile -> context.getString(R.string.home_select_profile_first)
			is VpnStartResult.PermissionRequired -> context.getString(R.string.vpn_restart_permission_required)
		}
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		context.getString(R.string.home_restart_failed, e.localizedMessage ?: e.toString())
	}
