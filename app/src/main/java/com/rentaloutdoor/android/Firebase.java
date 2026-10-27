package com.rentaloutdoor.android;

/*
  Smart WebView v8
  https://github.com/mgks/Android-SmartWebView

  A modern, open-source WebView wrapper for building advanced hybrid Android apps.
  Native features, modular plugins, and full customisation—built for developers.

  - Documentation: https://mgks.github.io/Android-SmartWebView/documentation
  - Plugins: https://mgks.github.io/Android-SmartWebView/documentation/plugins
  - Discussions: https://github.com/mgks/Android-SmartWebView/discussions
  - Sponsor the Project: https://github.com/sponsors/mgks

  MIT License — https://opensource.org/licenses/MIT

  Mentioning Smart WebView in your project helps others find it and keeps the dev loop alive.
*/

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class Firebase extends FirebaseMessagingService {

	private final String fcm_channel = SWVContext.asw_fcm_channel;

	/**
	 * Terjemahkan sinyal silent FCM (kind=rental_fine_start / rental_fine_stop)
	 * menjadi perintah RentalFineManager. Field payload identik dengan bridge
	 * web (startRentalFine) sehingga backend dan WebView mengendalikan notifikasi
	 * yang sama dengan formula denda yang sama.
	 */
	private static void handleRentalFineSignal(String kind, java.util.Map<String, String> data, Context context) {
		try {
			if ("rental_fine_stop".equals(kind)) {
				RentalFineManager.stop(context, data.get("rentalId"));
				return;
			}
			if ("rental_fine_start".equals(kind)) {
				org.json.JSONObject payload = new org.json.JSONObject();
				for (String key : java.util.Arrays.asList(
					"rentalId", "nomor", "jadwalKembali", "toleranceDeadline",
					"actualReturn", "currencyCode")) {
					String value = data.get(key);
					if (value != null) payload.put(key, value);
				}
				String rate = data.get("ratePerHour");
				if (rate != null && !rate.isEmpty()) payload.put("ratePerHour", Double.parseDouble(rate));
				String enabled = data.get("enabled");
				payload.put("enabled", "true".equalsIgnoreCase(enabled));
				RentalFineManager.start(context, payload);
				Log.d("Firebase", "rental_fine_start handled for " + payload.optString("rentalId"));
			}
		} catch (Exception e) {
			Log.e("Firebase", "rental fine signal gagal diproses", e);
		}
	}

	@Override
	public void onNewToken(String token) {
		super.onNewToken(token);
		Log.d("Firebase", "onNewToken() called");
		if (token != null && !token.isEmpty()) {
			SWVContext.fcm_token = token;
			Log.d("TOKEN_REFRESHED", "FCM token updated (length: " + token.length() + ")");
		} else {
			Log.d("TOKEN_REFRESHED", "NULL >> FAILED");
		}
	}

	@Override
	public void onMessageReceived(RemoteMessage message) {
		// Native rental-fine signals (silent data-only) drive the persistent
		// status-bar fine banner; they must never build a visible app push.
		java.util.Map<String, String> data = message.getData();
		if (data != null && !data.isEmpty()) {
			String kind = data.get("kind");
			if (kind != null && kind.startsWith("rental_fine")) {
				handleRentalFineSignal(kind, data, this);
				return;
			}
		}

		String title = null;
		String body = null;
		String uri = null;
		String click_action = "OPEN_URI";

		if (message.getNotification() != null) {
			title = message.getNotification().getTitle();
			body = message.getNotification().getBody();
			String notificationClickAction = message.getNotification().getClickAction();
			if (notificationClickAction != null && !notificationClickAction.isEmpty()) {
				click_action = notificationClickAction;
			}
		}

		if (message.getData() != null && !message.getData().isEmpty()) {
			String dataUri = message.getData().get("uri");
			if (dataUri != null && !dataUri.isEmpty()) {
				uri = dataUri;
			}
			String dataClickAction = message.getData().get("click_action");
			if (dataClickAction != null && !dataClickAction.isEmpty()) {
				click_action = dataClickAction;
			}
		}

		if (title == null) title = "Notification";
		if (body == null) body = "";
		if (uri == null || uri.isEmpty()) {
			uri = SWVContext.ASWV_URL;
		}

		Log.d("FCM_MESSAGE", "Title: " + title + ", Body: " + body + ", URI: " + uri + ", Click Action: " + click_action);

		sendMyNotification(title, body, click_action, uri, message.getData().get("tag"), message.getData().get("nid"), this);
	}

	public void sendMyNotification(String title, String message, String click_action, String uri, String tag, String nid, Context context) {
		// Create an intent based on the URI
		Intent intent;
		if (uri == null || uri.isEmpty() || uri.startsWith("file://")) {
			intent = new Intent(context, MainActivity.class);
		} else {
			intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
		}
		intent.setAction(click_action);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

		// Create a PendingIntent
		PendingIntent pendingIntent;
		pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		// Use a unique ID for each notification or a more robust default
		int notification_id = nid != null ? Integer.parseInt(nid) : SWVContext.ASWV_FCM_ID;

		// Build the notification
		NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context, fcm_channel)
			.setSmallIcon(R.drawable.ic_notification) // Use monochromatic notification icon
			.setContentTitle(title) // Remove notification ID from title
			.setContentText(message)
			.setAutoCancel(true)
			.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
			.setContentIntent(pendingIntent)
			.setPriority(NotificationCompat.PRIORITY_HIGH);

		// Get the NotificationManager
		NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

		// Create a notification channel for Android Oreo and above
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(fcm_channel, "SWV Channel", NotificationManager.IMPORTANCE_HIGH); // Use a more descriptive channel name
			notificationManager.createNotificationChannel(channel);
		}

		// Notify
		notificationManager.notify(notification_id, notificationBuilder.build());
	}
}
