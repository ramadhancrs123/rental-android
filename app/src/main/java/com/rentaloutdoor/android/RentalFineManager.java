package com.rentaloutdoor.android;

/*
  Rental Fine Manager
  Mengelola ongoing notification "Denda Penyewaan" di status bar & lock screen
  yang menampilkan jumlah denda berjalan (bukan countdown waktu).

  Formula denda 100% identik dengan backend (calculate_rental_late_fee):
    overdue_ms   = max(0, (actual_return ?? now) - cutof)
    cutoff       = tolerance_deadline ?? jadwal_kembali
    billable_hrs = ceil(overdue_ms / 3600000)
    amount       = billable_hrs * rate_per_hour   (0 jika disabled)
  Jumlah hanya berubah tiap jam penuh -> update via AlarmManager
  setAndAllowWhileIdle tepat pada batas jam berikutnya (hemat baterai, tanpa
  foreground service, tetap jalan di Doze).
 */

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

public class RentalFineManager {

	private static final String TAG = "RentalFine";
	private static final String PREFS = "rental_fine_state";
	private static final String CHANNEL_ID = "rental_fine_channel";
	private static final int NOTIF_ID = 47210;
	private static final long HOUR_MS = 3_600_000L;

	// Persisted state keys
	private static final String K_RENTAL_ID = "rental_id";
	private static final String K_NOMOR = "nomor";
	private static final String K_RATE = "rate";
	private static final String K_ENABLED = "enabled";
	private static final String K_CUTOFF = "cutoff";
	private static final String K_RETURN = "actual_return";
	private static final String K_CURRENCY = "currency";

	// ---------------------------------------------------------------
	// Bridged entry points (dipanggil dari AndroidInterface/WebView)
	// ---------------------------------------------------------------

	public static void start(Context context, JSONObject payload) {
		if (context == null || payload == null) return;
		try {
			String rentalId = payload.optString("rentalId", "");
			if (rentalId.isEmpty()) {
				Log.w(TAG, "startRentalFine: rentalId wajib diisi");
				return;
			}

			boolean enabled = payload.optBoolean("enabled", false);
			double rate = payload.optDouble("ratePerHour", 0d);
			String jadwal = payload.optString("jadwalKembali", null);
			String tolerance = payload.optString("toleranceDeadline", null);
			String actualReturn = payload.optString("actualReturn", null);
			String currency = payload.optString("currencyCode", "IDR");

			Long cutoffMs = parseIso(iso(tolerance));
			Long scheduleMs = parseIso(iso(jadwal));
			if (cutoffMs == null) cutoffMs = scheduleMs;

			// Aturan tampil: hanya denda aktif; tanpa denda atau tanpa cutoff -> stop.
			if (!enabled || rate <= 0 || cutoffMs == null) {
				stop(context, rentalId);
				return;
			}

			Long returnMs = parseIso(iso(actualReturn));
			// Rental sudah kembali & tidak ada kelebihan waktu -> tidak ada denda, stop.
			if (returnMs != null && returnMs <= cutoffMs) {
				stop(context, rentalId);
				return;
			}

			SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
			prefs.edit()
				.putString(K_RENTAL_ID, rentalId)
				.putString(K_NOMOR, payload.optString("nomor", ""))
				.putLong(K_RATE, Double.doubleToRawLongBits(rate))
				.putBoolean(K_ENABLED, true)
				.putLong(K_CUTOFF, cutoffMs)
				.putLong(K_RETURN, returnMs == null ? -1L : returnMs)
				.putString(K_CURRENCY, currency)
				.apply();

			tick(context);
		} catch (Exception e) {
			Log.e(TAG, "startRentalFine gagal", e);
		}
	}

	public static void stop(Context context, String rentalId) {
		SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		String activeId = prefs.getString(K_RENTAL_ID, null);
		if (rentalId != null && !rentalId.isEmpty() && activeId != null && !activeId.equals(rentalId)) {
			return; // notifikasi berbeda penyewaan; jangan ganggu
		}
		prefs.edit().clear().apply();
		cancelAlarm(context);
		NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm != null) nm.cancel(NOTIF_ID);
		Log.d(TAG, "stopRentalFine: " + rentalId);
	}

	// ---------------------------------------------------------------
	// Receiver hooks
	// ---------------------------------------------------------------

	public static void onTick(Context context) {
		tick(context);
	}

	public static void onBoot(Context context) {
		tick(context);
	}

	// ---------------------------------------------------------------
	// Core scheduling + posting
	// ---------------------------------------------------------------

	private static void tick(Context context) {
		SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		String rentalId = prefs.getString(K_RENTAL_ID, null);
		if (rentalId == null) return;

		double rate = Double.longBitsToDouble(prefs.getLong(K_RATE, 0L));
		long cutoff = prefs.getLong(K_CUTOFF, 0L);
		long returnAt = prefs.getLong(K_RETURN, -1L);
		if (cutoff <= 0L || rate <= 0d) return;

		long now = System.currentTimeMillis();
		long endDate = returnAt >= 0L ? returnAt : now;
		long overdueMs = endDate - cutoff;

		if (overdueMs <= 0L) {
			// Belum ada dena: mati sampai cutoff lewat (hanya saat rental berjalan)
			if (returnAt < 0L) scheduleAt(context, cutoff + 1_000L);
			cancelNotification(context);
			return;
		}

		long billableHours = (overdueMs + HOUR_MS - 1L) / HOUR_MS; // ceil, identik backend
		if (billableHours <= 0L) {
			cancelNotification(context);
			return;
		}
		long amount = Math.round(billableHours * rate);
		postNotification(context, prefs, amount, billableHours, returnAt >= 0L);

		// Update berikutnya hanya perlu saat batas jam berikutnya lewat.
		if (returnAt < 0L) {
			scheduleAt(context, cutoff + billableHours * HOUR_MS);
		} else {
			cancelAlarm(context); // final; tidak ada update lebih
		}
	}

	private static void postNotification(Context context, SharedPreferences prefs, long amount, long hours, boolean isFinal) {
		String nomor = prefs.getString(K_NOMOR, "");
		String rentalId = prefs.getString(K_RENTAL_ID, "");
		String currency = prefs.getString(K_CURRENCY, "IDR");
		String money = formatMoney(currency, amount);
		String statusLine = isFinal
			? "Final saat pengembalian"
			: "Terlambat " + hours + " jam (pembulatan per jam)";

		NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm == null) return;
		if (Build.VERSION.SDK_INT >= 26) {
			NotificationChannel channel = new NotificationChannel(
				CHANNEL_ID, "Denda Penyewaan", NotificationManager.IMPORTANCE_DEFAULT);
			channel.setDescription("Notifikasi denda penyewaan yang sedang berjalan");
			channel.enableVibration(false);
			channel.setSound(null, null);
			nm.createNotificationChannel(channel);
		}

		NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle("Denda " + money)
			.setContentText("Penyewaan " + nomor + " • " + statusLine)
			.setStyle(new NotificationCompat.BigTextStyle()
				.bigText("Penyewaan " + nomor + "\n" + statusLine + "\nDenda berjalan: " + money))
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setSilent(true)
			.setLocalOnly(true)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // tampil di lockscreen
			.setContentIntent(contentIntent(context, rentalId))
			.setAutoCancel(false);

		Notification notification = builder.build();
		notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
		nm.notify(NOTIF_ID, notification);
		if (SWVContext.SWV_DEBUGMODE) {
			Log.d(TAG, "Notif denda: " + money + " (" + hours + " jam)");
		}
	}

	private static PendingIntent contentIntent(Context context, String rentalId) {
		Intent intent;
		String base = SWVContext.ASWV_APP_URL;
		if (base == null || base.isEmpty()) base = "https://akasha-outdoor.vercel.app/";
		String url = sanitizeBase(base) + "penyewaan/" + rentalId;
		intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		return PendingIntent.getActivity(
			context, NOTIF_ID, intent,
			PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	private static String sanitizeBase(String base) {
		while (base != null && base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return base + "/";
	}

	// ---------------------------------------------------------------
	// Alarm scheduling (bordes-dozable)
	// ---------------------------------------------------------------

	private static void scheduleAt(Context context, long triggerAtMillis) {
		cancelAlarm(context);
		AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		if (am == null) return;
		Intent intent = new Intent(context, TickReceiver.class);
		intent.setAction("com.rentaloutdoor.android.RENTAL_FINE_TICK");
		PendingIntent pi = PendingIntent.getBroadcast(
			context, 1, intent,
			PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		if (Build.VERSION.SDK_INT >= 23) {
			am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(triggerAtMillis, System.currentTimeMillis() + 1_000L), pi);
		} else {
			am.set(AlarmManager.RTC_WAKEUP, Math.max(triggerAtMillis, System.currentTimeMillis() + 1_000L), pi);
		}
		if (SWVContext.SWV_DEBUGMODE) {
			Log.d(TAG, "Alarm tick dijadwalkan: " + new java.util.Date(triggerAtMillis));
		}
	}

	private static void cancelAlarm(Context context) {
		AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		if (am == null) return;
		Intent intent = new Intent(context, TickReceiver.class);
		intent.setAction("com.rentaloutdoor.android.RENTAL_FINE_TICK");
		PendingIntent pi = PendingIntent.getBroadcast(
			context, 1, intent,
			PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		am.cancel(pi);
	}

	private static void cancelNotification(Context context) {
		NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm != null) nm.cancel(NOTIF_ID);
	}

	// ---------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------

	/** iOS/RFC3339 dengan/atau tanpa offset -> epoch millis (parse sederhana). */
	private static Long parseIso(String iso) {
		if (iso == null || iso.isEmpty()) return null;
		try {
			return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).parse(iso).getTime();
		} catch (Exception ignored) {
			// fallback tanpa milis
			try {
				return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).parse(iso).getTime();
			} catch (Exception e) {
				Log.e(TAG, "parseIso gagal: " + iso, e);
				return null;
			}
		}
	}

	/** normalisasi null/undefined string ke null. */
	private static String iso(String raw) {
		if (raw == null || raw.isEmpty() || "null".equalsIgnoreCase(raw) || "undefined".equalsIgnoreCase(raw)) return null;
		return raw;
	}

	private static String formatMoney(String currencyCode, long amount) {
		NumberFormat fmt = NumberFormat.getIntegerInstance(new Locale("in", "ID"));
		if ("IDR".equalsIgnoreCase(currencyCode)) return "Rp " + fmt.format(amount) + ",-";
		return currencyCode + " " + fmt.format(amount);
	}

	// ---------------------------------------------------------------
	// Boot receiver (persist state agar notif hidup lagi setelah reboot)
	// ---------------------------------------------------------------

	public static class BootReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
			onBoot(context);
		}
	}

	// ---------------------------------------------------------------
	// Tick receiver (alarm tiap batas jam)
	// ---------------------------------------------------------------

	public static class TickReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			onTick(context);
		}
	}
}
