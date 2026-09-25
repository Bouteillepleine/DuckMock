package com.strawing.duckmock.common

object Config {
    const val PKG = "com.strawing.duckmock"
    const val MODULE_ID = "duckmock"
    const val MODULE_VERSION = "1.0.0"

    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    const val MODULE_UPDATE_DIR = "/data/adb/modules_update/$MODULE_ID"
    const val CONFIG_FILE = "$MODULE_DIR/config.json"
    const val DISABLE_FILE = "$MODULE_DIR/disable_hooks"
    const val BOOT_COUNT_FILE = "$MODULE_DIR/boot_attempts"
    const val MAX_BOOT_ATTEMPTS = 3

    const val SYSTEM_SERVER_PACKAGE = "android"

    const val FIRST_APP_UID = 10000
    const val PER_USER_RANGE = 100000
    const val SYSTEM_UID = 1000

    const val SETTINGS_AUTHORITY = "settings"
    const val CALL_VALUE = "value"
    const val CALL_GENERATION_INDEX = "_generation_index"
    const val SETTINGS_PROVIDER = "com.android.providers.settings.SettingsProvider"

    const val MOCK_LOCATION_KEY = "mock_location"
    const val OPSTR_MOCK_LOCATION = "android:mock_location"
    const val OP_MOCK_LOCATION_FALLBACK = 58

    const val MODE_ALLOWED = 0
    const val MODE_IGNORED = 1
    const val MODE_ERRORED = 2

    const val LOCATION_CLASS = "android.location.Location"
    const val APP_OPS_SERVICE_CLASS = "com.android.server.appop.AppOpsService"
    const val APP_OPS_SERVICE_LEGACY_CLASS = "com.android.server.AppOpsService"

    const val MASK_FIELD = "mFieldsMask"
    const val MASK_CONSTANT = "HAS_MOCK_PROVIDER_MASK"
    const val EXTRAS_FIELD = "mExtras"
    const val MOCK_EXTRA = "mockLocation"

    val GET_METHODS = setOf("GET_secure", "GET_global", "GET_system")

    val LOCATION_MARK_METHODS = listOf("setMock", "setIsMock", "setIsFromMockProvider")

    val KNOWN_PROVIDERS = setOf("gps", "network", "passive", "fused")

    val SPARE_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.settings",
        "com.android.systemui",
        "com.android.shell",
        "com.android.phone",
        "com.android.providers.settings",
        "com.android.location.fused",
    )

    const val RECORD_LOCATION = "location"
    const val RECORD_SETTINGS = "settings"
    const val RECORD_QUERY = "query"
    const val RECORD_APPOPS = "appops"
    const val RECORD_GRANT = "grant"
}
