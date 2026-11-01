package de.minipc.appopsshizuku

import android.app.admin.DeviceAdminReceiver

/**
 * Geraeteadmin-Empfaenger, damit App Ops Shizuku Profilbesitzer eines selbst angelegten
 * Arbeitsprofils werden kann (dpm set-profile-owner). Keine eigenen Richtlinien noetig.
 */
class ProfileOwnerReceiver : DeviceAdminReceiver()
