package com.urlxl.mail.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.urlxl.mail.MainActivity
import com.urlxl.mail.R

object PushNotificationDispatcher {
    private const val CHANNEL_ID = "kypost_push"
    private const val MFA_CHANNEL_ID = "kypost_mfa"

    const val ACTION_MFA_APPROVE = "com.urlxl.mail.push.ACTION_MFA_APPROVE"
    const val ACTION_MFA_DENY = "com.urlxl.mail.push.ACTION_MFA_DENY"
    const val EXTRA_MFA_CHALLENGE_ID = "challengeId"
    const val EXTRA_MESSAGE_ID = "com.urlxl.mail.push.EXTRA_MESSAGE_ID"
    const val EXTRA_SENDER = "com.urlxl.mail.push.EXTRA_SENDER"
    const val EXTRA_SUBJECT = "com.urlxl.mail.push.EXTRA_SUBJECT"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "KyPost",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Push notifications for labeled email events"
        }
        manager.createNotificationChannel(channel)
    }

    fun ensureMfaChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(MFA_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            MFA_CHANNEL_ID,
            "Sign-in approvals",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Approve or deny sign-in attempts to your account"
        }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, payload: PushPayload) {
        ensureChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val launchIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_MESSAGE_ID, payload.messageId)
            .putExtra(EXTRA_SENDER, payload.senderName)
            .putExtra(EXTRA_SUBJECT, payload.emailSubject)
            .putExtra(EXTRA_MESSAGE_ID, payload.messageId)

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            payload.messageId.hashCode(),
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(PushPayloadParser.title(payload))
            .setContentText(PushPayloadParser.body(payload))
            .setStyle(NotificationCompat.BigTextStyle().bigText(PushPayloadParser.body(payload)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(payload.messageId.hashCode(), notification)
    }

    fun showMfaChallenge(context: Context, payload: MfaChallengePayload) {
        ensureMfaChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val notificationId = mfaNotificationId(payload.challengeId)

        val tapIntent = Intent(context, MfaApprovalActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_MFA_CHALLENGE_ID, payload.challengeId)
        val tapPendingIntent = android.app.PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val approveIntent = Intent(context, MfaResponseReceiver::class.java)
            .setAction(ACTION_MFA_APPROVE)
            .putExtra(EXTRA_MFA_CHALLENGE_ID, payload.challengeId)
        val approvePendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            notificationId * 2,
            approveIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val denyIntent = Intent(context, MfaResponseReceiver::class.java)
            .setAction(ACTION_MFA_DENY)
            .putExtra(EXTRA_MFA_CHALLENGE_ID, payload.challengeId)
        val denyPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            notificationId * 2 + 1,
            denyIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, MFA_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Approve sign-in")
            .setContentText("Tap to approve or deny a sign-in to your account.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .addAction(0, "Approve", approvePendingIntent)
            .addAction(0, "Deny", denyPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun cancelMfaChallenge(context: Context, challengeId: String) {
        NotificationManagerCompat.from(context).cancel(mfaNotificationId(challengeId))
    }

    private fun mfaNotificationId(challengeId: String): Int = ("mfa-$challengeId").hashCode()
}
