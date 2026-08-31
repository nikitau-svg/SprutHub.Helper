package io.github.nikitau.spruthubhelper.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.pm.PackageInfoCompat
import io.github.nikitau.spruthubhelper.BuildConfig

object DiagnosticSnapshotFactory {
    fun capture(
        context: Context,
        events: List<DiagnosticEvent>,
        healthConnectAvailable: Boolean,
        healthGrantedPermissions: Set<String>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): DiagnosticSnapshot {
        val appContext = context.applicationContext
        val packageInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        return DiagnosticSnapshot(
            generatedAtEpochMs = nowEpochMs,
            appVersion = packageInfo.versionName ?: BuildConfig.VERSION_NAME,
            appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
            androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "неизвестно" },
            androidSdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "неизвестно" },
            model = Build.MODEL.orEmpty().ifBlank { "неизвестно" },
            batteryOptimization = batteryOptimizationState(appContext),
            backgroundRestriction = backgroundRestrictionState(appContext),
            notificationState = notificationState(appContext),
            networkState = networkState(appContext),
            permissions = permissionStates(
                context = appContext,
                requestedPermissions = packageInfo.requestedPermissions.orEmpty(),
                healthConnectAvailable = healthConnectAvailable,
                healthGrantedPermissions = healthGrantedPermissions,
            ),
            events = events.map(DiagnosticRedactor::redact),
        )
    }

    private fun batteryOptimizationState(context: Context): String {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) {
            "исключено из оптимизации"
        } else {
            "оптимизация включена; фоновые запуски могут откладываться"
        }
    }

    private fun backgroundRestrictionState(context: Context): String {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return if (activityManager?.isBackgroundRestricted == true) {
            "Android ограничивает фоновую работу"
        } else {
            "явного системного ограничения нет"
        }
    }

    private fun notificationState(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        return when {
            !runtimeGranted -> "runtime-разрешение не выдано"
            manager?.areNotificationsEnabled() != true -> "отключены в настройках Android"
            else -> "разрешены"
        }
    }

    private fun networkState(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "неизвестно"
        val network = manager.activeNetwork ?: return "нет активной сети"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "активная сеть без сведений"
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "другая"
        }
        val validated = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            "доступ в интернет подтверждён"
        } else {
            "доступ в интернет не подтверждён"
        }
        val metered = if (manager.isActiveNetworkMetered) "лимитная" else "без лимита"
        return "$transport, $validated, $metered"
    }

    private fun permissionStates(
        context: Context,
        requestedPermissions: Array<out String>,
        healthConnectAvailable: Boolean,
        healthGrantedPermissions: Set<String>,
    ): List<DiagnosticPermissionStatus> {
        val healthPermissions = requestedPermissions
            .filter { it.startsWith("android.permission.health.") }
            .distinct()
        val healthGranted = healthPermissions.count(healthGrantedPermissions::contains)
        return buildList {
            add(
                DiagnosticPermissionStatus(
                    "Уведомления",
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        "отдельное runtime-разрешение не требуется"
                    } else {
                        grantedTitle(isGranted(context, Manifest.permission.POST_NOTIFICATIONS))
                    },
                ),
            )
            add(
                DiagnosticPermissionStatus(
                    "Точная геопозиция",
                    grantedTitle(isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)),
                ),
            )
            add(
                DiagnosticPermissionStatus(
                    "Примерная геопозиция",
                    grantedTitle(isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)),
                ),
            )
            add(
                DiagnosticPermissionStatus(
                    "Геопозиция в фоне",
                    grantedTitle(isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
                ),
            )
            add(
                DiagnosticPermissionStatus(
                    "Health Connect",
                    when {
                        !healthConnectAvailable -> "недоступен на этом устройстве"
                        healthPermissions.isEmpty() -> "разрешения не заявлены"
                        else -> "$healthGranted из ${healthPermissions.size} выдано"
                    },
                ),
            )
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun grantedTitle(granted: Boolean): String = if (granted) "выдано" else "не выдано"
}
