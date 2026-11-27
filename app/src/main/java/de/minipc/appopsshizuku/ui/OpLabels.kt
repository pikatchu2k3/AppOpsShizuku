package de.minipc.appopsshizuku.ui

import android.content.Context
import de.minipc.appopsshizuku.R
import de.minipc.appopsshizuku.appops.AppOp
import de.minipc.appopsshizuku.appops.PrivacyOps

/**
 * Lokalisierte Anzeigenamen der Privacy-Ops. Der Katalog ([PrivacyOps]) bleibt Android-frei
 * (JVM-Tests) und traegt nur einen deutschen Rueckfall-Namen; angezeigt wird immer der Text aus
 * `strings.xml` in der Sprache des Geraets.
 */
object OpLabels {

    private val RES: Map<String, Int> = mapOf(
        "CAMERA" to R.string.op_label_camera,
        "RECORD_AUDIO" to R.string.op_label_record_audio,
        "READ_CONTACTS" to R.string.op_label_read_contacts,
        "WRITE_CONTACTS" to R.string.op_label_write_contacts,
        "READ_CALENDAR" to R.string.op_label_read_calendar,
        "WRITE_CALENDAR" to R.string.op_label_write_calendar,
        "READ_CALL_LOG" to R.string.op_label_read_call_log,
        "WRITE_CALL_LOG" to R.string.op_label_write_call_log,
        "GET_ACCOUNTS" to R.string.op_label_get_accounts,
        "READ_CLIPBOARD" to R.string.op_label_read_clipboard,
        "WRITE_CLIPBOARD" to R.string.op_label_write_clipboard,
        "BODY_SENSORS" to R.string.op_label_body_sensors,
        "ACTIVITY_RECOGNITION" to R.string.op_label_activity_recognition,
        "READ_PHONE_STATE" to R.string.op_label_read_phone_state,
        "READ_SMS" to R.string.op_label_read_sms,
        "RECEIVE_SMS" to R.string.op_label_receive_sms,
        "BLUETOOTH_SCAN" to R.string.op_label_bluetooth_scan,
        "NEARBY_WIFI_DEVICES" to R.string.op_label_nearby_wifi_devices,
        "ACCESS_LOCAL_NETWORK" to R.string.op_label_access_local_network,
        "FINE_LOCATION" to R.string.op_label_fine_location,
        "COARSE_LOCATION" to R.string.op_label_coarse_location,
        "ACCESS_BACKGROUND_LOCATION" to R.string.op_label_access_background_location,
        "ACCESS_MEDIA_LOCATION" to R.string.op_label_access_media_location,
        "READ_MEDIA_IMAGES" to R.string.op_label_read_media_images,
        "READ_MEDIA_VIDEO" to R.string.op_label_read_media_video,
        "READ_MEDIA_AUDIO" to R.string.op_label_read_media_audio,
        "READ_MEDIA_VISUAL_USER_SELECTED" to R.string.op_label_read_media_visual_user_selected,
        "MANAGE_EXTERNAL_STORAGE" to R.string.op_label_manage_external_storage,
        "RUN_IN_BACKGROUND" to R.string.op_label_run_in_background,
        "RUN_ANY_IN_BACKGROUND" to R.string.op_label_run_any_in_background,
        "START_FOREGROUND_SERVICES_FROM_BACKGROUND" to R.string.op_label_start_fgs_from_background,
        "GET_USAGE_STATS" to R.string.op_label_get_usage_stats,
        "READ_DEVICE_IDENTIFIERS" to R.string.op_label_read_device_identifiers,
        "MOCK_LOCATION" to R.string.op_label_mock_location,
        "SYSTEM_ALERT_WINDOW" to R.string.op_label_system_alert_window,
        "ACCESS_NOTIFICATIONS" to R.string.op_label_access_notifications,
        "POST_NOTIFICATION" to R.string.op_label_post_notification,
        "TURN_SCREEN_ON" to R.string.op_label_turn_screen_on,
        "PICTURE_IN_PICTURE" to R.string.op_label_picture_in_picture,
        "BLUETOOTH_CONNECT" to R.string.op_label_bluetooth_connect,
        "READ_PHONE_NUMBERS" to R.string.op_label_read_phone_numbers,
        "CALL_PHONE" to R.string.op_label_call_phone,
        "SEND_SMS" to R.string.op_label_send_sms,
        "READ_EXTERNAL_STORAGE" to R.string.op_label_read_external_storage,
        "WRITE_EXTERNAL_STORAGE" to R.string.op_label_write_external_storage,
        "REQUEST_INSTALL_PACKAGES" to R.string.op_label_request_install_packages,
        "WRITE_SETTINGS" to R.string.op_label_write_settings,
        "SCHEDULE_EXACT_ALARM" to R.string.op_label_schedule_exact_alarm,
        "USE_FULL_SCREEN_INTENT" to R.string.op_label_use_full_screen_intent
    )

    /** Op-Namen, fuer die ein lokalisierter Text existiert (fuer den Abdeckungs-Test). */
    val covered: Set<String> get() = RES.keys

    fun of(context: Context, op: AppOp): String =
        RES[op.opName]?.let { context.getString(it) } ?: op.label

    /** Katalog mit lokalisierten Namen (opName/permissionName bleiben unveraendert). */
    fun localized(context: Context, ops: List<AppOp> = PrivacyOps.PRIVACY_OPS): List<AppOp> =
        ops.map { it.copy(label = of(context, it)) }
}
