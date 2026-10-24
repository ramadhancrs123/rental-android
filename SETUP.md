# Rental Outdoor Android App - Setup Guide (Bahasa Indonesia)

Project ini berbasis **Smart WebView v8** template (https://github.com/mgks/Android-SmartWebView).

## ✅ Sudah Dikonfigurasi

- [x] Package name: `com.rentaloutdoor.android`
- [x] Nama aplikasi: "Rental Outdoor"
- [x] URL aplikasi: `https://akasha-outdoor.vercel.app/`
- [x] Warna brand (Hijau/Oranye)
- [x] Custom CSS untuk styling WebView
- [x] Dukungan upload kamera
- [x] Upload multiple file
- [x] Pull-to-refresh
- [x] Progress bar
- [x] Dukungan zoom
- [x] Autofill form
- [x] Push notifications (Firebase)
- [x] Layanan lokasi/GPS
- [x] Scanner QR Code
- [x] Autentikasi biometrik
- [x] Kompresi gambar
- [x] Berbagi file
- [x] Akses clipboard
- [x] Info jaringan
- [x] Notifikasi toast
- [x] Plugin JavaScript interface
- [x] Dukungan halaman offline
- [x] **Supabase Realtime (WebSocket)**
- [x] Logo: `assets/web/logo.png`

## 🔔 **SISTEM NOTIFIKASI LENGKAP (NEW)**

### Arsitektur Notifikasi

```
┌─────────────────────────────────────────────────────────────────┐
│                    SUPABASE DATABASE                            │
├─────────────────────────────────────────────────────────────────┤
│  Tables Baru:                                                   │
│  ├── user_devices              - FCM tokens (admin & investor) │
│  ├── fcm_notification_queue    - Queue untuk Edge Function      │
│  └── outbox_event (extended)   - Event types baru              │
├─────────────────────────────────────────────────────────────────┤
│  Triggers Baru:                                                 │
│  ├── trg_penyewaan_notification_events      - rental.* events  │
│  ├── trg_pembayaran_completed_event         - payment.completed │
│  ├── trg_temuan_pemeriksaan_damage_event    - damage.found     │
│  ├── trg_perpanjangan_notification_event    - extension.*      │
│  ├── trg_investor_distribution_profit_shared - profit.shared   │
│  └── trg_investor_payout_completed          - payout.completed │
├─────────────────────────────────────────────────────────────────┤
│  Cron Jobs Baru:                                                │
│  ├── rental-deadline-notifier     - Tiap menit (return due,    │
│  │                                   tolerance 2h/1h/30m,      │
│  │                                   tolerance started/expired) │
│  ├── damage-check-scheduled       - Tiap 6 jam (cek kerusakan) │
│  ├── late-fee-processing          - Harian jam 2 AM (denda)    │
│  ├── notification-outbox-consumer - Tiap menit (proses queue)  │
│  └── fcm-queue-processor          - Tiap menit (kirim FCM)     │
├─────────────────────────────────────────────────────────────────┤
│  Edge Functions:                                                │
│  ├── send-push-notification  - Kirim FCM via HTTP v1 API       │
│  ├── process-fcm-queue       - Proses queue batch               │
│  └── process-fcm-queue-cron  - Cron job panggil processor      │
└─────────────────────────────────────────────────────────────────┘
```

### Event Types Notifikasi (Hanya ke Admin & Investor)

| Event Type | Trigger | Penerima | Deskripsi |
|------------|---------|----------|-----------|
| `rental.created` | INSERT penyewaan | Admin | Penyewaan baru dibuat |
| `payment.completed` | pembayaran status=completed | Admin | Pembayaran berhasil |
| `rental.return_due` | Cron (waktu kembali tiba) | Admin | Waktu kembali tiba |
| `rental.tolerance_started` | Cron (waktu kembali tiba) | Admin | Masa toleransi dimulai |
| `rental.tolerance_warning_2h` | Cron (2 jam sebelum habis) | Admin | Toleransi 2 jam lagi |
| `rental.tolerance_warning_1h` | Cron (1 jam sebelum habis) | Admin | Toleransi 1 jam lagi |
| `rental.tolerance_warning_30m` | Cron (30 menit sebelum habis) | Admin | Toleransi 30 menit lagi |
| `rental.tolerance_expired` | Cron (toleransi habis) | Admin | Masa toleransi habis |
| `rental.late_fee_applied` | Cron harian (overdue) | Admin | Denda keterlambatan |
| `rental.extension_requested` | INSERT perpanjangan | Admin | Permintaan perpanjangan |
| `rental.extension_approved` | UPDATE perpanjangan=approved | Admin | Perpanjangan disetujui |
| `rental.extension_rejected` | UPDATE perpanjangan=rejected | Admin | Perpanjangan ditolak |
| `damage.found` | INSERT temuan kerusakan | Admin | Kerusakan ditemukan |
| `damage.check_scheduled` | Cron 6 jam | Admin | Cek berkala kerusakan |
| `profit.shared` | INSERT investor_distribution | Investor | Bagi hasil investor |
| `payout.completed` | INSERT stakeholder_payout | Investor | Pembayaran ke stakeholder |

---

## 🔧 Langkah Setup yang Diperlukan

### 1. Firebase Push Notifications

1. Buka [Firebase Console](https://console.firebase.google.com/)
2. Buat project baru atau pilih yang sudah ada
3. Tambah aplikasi Android:
   - Package name: `com.rentaloutdoor.android`
   - Nickname aplikasi: "Rental Outdoor Android"
   - SHA-1: Dapatkan dari `./gradlew signingReport` (untuk build release)
4. Download `google-services.json`
5. Tempatkan di: `rental-android/app/google-services.json`
6. Aktifkan **Cloud Messaging** di Firebase Console
7. Salin **Server Key** dan **Sender ID** untuk backend Anda
8. **Project ID** Firebase (untuk Edge Function)

### 2. Supabase Edge Functions - Set Secrets

Di **Supabase Dashboard → Edge Functions → Settings → Secrets**, tambahkan:

| Secret Name | Value | Deskripsi |
|-------------|-------|-----------|
| `FIREBASE_SERVER_KEY` | `AAAA...` | Server Key dari Firebase Console → Cloud Messaging |
| `FIREBASE_PROJECT_ID` | `your-project-id` | Project ID Firebase (bukan App ID) |
| `SUPABASE_URL` | `https://nbirkybutpvtrqifaxlt.supabase.co` | Auto-set by Supabase |
| `SUPABASE_SERVICE_ROLE_KEY` | `eyJ...` | Auto-set by Supabase |

### 3. App Icons & Splash Screen

Ganti file berikut dengan aset brand Anda:

```
app/src/main/res/mipmap-mdpi/ic_launcher.webp (48x48)
app/src/main/res/mipmap-hdpi/ic_launcher.webp (72x72)
app/src/main/res/mipmap-xhdpi/ic_launcher.webp (96x96)
app/src/main/res/mipmap-xxhdpi/ic_launcher.webp (144x144)
app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp (192x192)

app/src/main/res/mipmap-*/ic_launcher_foreground.webp (adaptive icon foreground)
app/src/main/res/mipmap-*/ic_launcher_background.webp (adaptive icon background)

app/src/main/assets/web/swv_splash.png (splash screen light mode)
app/src/main/assets/web/swv_splash_white.png (splash screen dark mode)
```

**Logo sudah tersedia di:** `app/src/main/assets/web/logo.png` (458KB)

**Tool direkomendasikan:** [Android Asset Studio](https://romannurik.github.io/AndroidAssetStudio/) untuk adaptive icons.

### 4. AdMob (Opsional - untuk monetisasi)

1. Buat akun AdMob di [admob.google.com](https://admob.google.com)
2. Buat aplikasi dan unit iklan
3. Update `app/src/main/res/values/ads.xml`:
   ```xml
   <string name="admob_app_id">ca-app-pub-YOUR_APP_ID~YOUR_ID</string>
   ```
4. Tambah ID unit iklan di panel admin web Anda

### 5. Google Analytics (Opsional)

Update `app/src/main/assets/swv.properties`:
```properties
analytics.gtag.id=G-YOUR_MEASUREMENT_ID
```

### 6. Keystore untuk Build Release

1. Generate keystore:
   ```bash
   keytool -genkey -v -keystore rental-outdoor-release.keystore -alias rental-outdoor -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Buat `keystore.properties` di root project (JANGAN DI-COMMIT):
   ```properties
   storeFile=rental-outdoor-release.keystore
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=rental-outdoor
   keyPassword=YOUR_KEY_PASSWORD
   ```
3. Update `app/build.gradle` untuk menggunakan signing config (sudah dikonfigurasi di template)

### 7. Deep Links / App Links (Opsional)

Agar link `https://akasha-outdoor.vercel.app/` terbuka di aplikasi:
1. Tambah Digital Asset Links ke website Anda:
   - Generate `assetlinks.json` dari Play Console
   - Host di `https://akasha-outdoor.vercel.app/.well-known/assetlinks.json`
2. Update `AndroidManifest.xml` intent-filter dengan domain Anda

### 8. Privacy Policy & Terms

Tambah URL ke panel admin web Anda untuk:
- Kebijakan Privasi
- Syarat & Ketentuan
- Permintaan penghapusan data

---

## 📱 **INTEGRASI ANDROID APP - FCM TOKEN SAVING**

### Di Web Admin Panel (akasha-outdoor.vercel.app)

Tambahkan kode JavaScript berikut untuk menyimpan FCM token ke Supabase:

```javascript
// Di halaman login/dashboard admin panel
async function saveFCMTokenToSupabase() {
  try {
    // 1. Dapatkan FCM token dari Android app via JSInterface
    const token = await getFCMTokenFromAndroid();
    
    if (!token) {
      console.log('FCM token not available (not running in Android app)');
      return;
    }

    // 2. Kirim ke Supabase via REST API atau Supabase JS Client
    const response = await fetch('/api/save-fcm-token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        fcm_token: token,
        platform: 'android',
        device_info: {
          user_agent: navigator.userAgent,
          app_version: '1.0.0', // dari BuildConfig.VERSION_NAME
        }
      })
    });

    if (response.ok) {
      console.log('FCM token saved successfully');
    }
  } catch (error) {
    console.error('Failed to save FCM token:', error);
  }
}

// Fungsi untuk mendapatkan token dari Android app
function getFCMTokenFromAndroid() {
  return new Promise((resolve) => {
    // Via JSInterfacePlugin (sudah enabled di swv.properties)
    if (window.SWV && window.SWV.getFCMToken) {
      window.SWV.getFCMToken((token) => resolve(token));
    } else if (window.android && window.android.getFCMToken) {
      // Fallback untuk interface lama
      window.android.getFCMToken((token) => resolve(token));
    } else {
      resolve(null);
    }
  });
}

// Panggil saat user login atau dashboard load
document.addEventListener('DOMContentLoaded', () => {
  // Delay sedikit untuk memastikan WebView ready
  setTimeout(saveFCMTokenToSupabase, 1000);
});
```

### Di Supabase (Backend API)

Buat endpoint `/api/save-fcm-token` di Supabase Edge Function atau gunakan PostgREST:

```sql
-- Via Supabase JS Client di web admin panel
const { data, error } = await supabase
  .from('user_devices')
  .upsert({
    usaha_id: currentUsahaId, // dari session/user context
    user_type: 'admin', // atau 'investor'
    user_id: currentUserId, // akun_admin_id atau investor_id
    fcm_token: token,
    platform: 'android',
    device_info: deviceInfo,
    is_active: true,
    last_used_at: new Date().toISOString()
  }, {
    onConflict: 'usaha_id,user_type,user_id,fcm_token'
  });
```

### Di Android App (Firebase.java)

Pastikan `Firebase.java` sudah handle token refresh (sudah ada di template):

```java
// Firebase.java - onNewToken sudah implementasi
@Override
public void onNewToken(String token) {
    super.onNewToken(token);
    SWVContext.fcm_token = token; // Token tersimpan di context
    // Token akan dikirim ke web via JSInterface saat web request
}
```

---

## 📋 File Penting (Updated)

| File | Fungsi |
|------|--------|
| `app/src/main/assets/swv.properties` | Konfigurasi utama aplikasi |
| `app/src/main/res/values/strings.xml` | String aplikasi (Bahasa Indonesia) |
| `app/src/main/res/values/colors.xml` | Warna brand |
| `app/src/main/assets/web/custom.css` | Style kustom WebView |
| `app/src/main/assets/web/logo.png` | Logo aplikasi |
| `app/src/main/assets/web/offline.html` | Halaman offline |
| `app/src/main/AndroidManifest.xml` | Permissions & activities |
| `app/build.gradle` | Dependencies & build config |
| `app/google-services.json` | Firebase config (Anda tambahkan) |
| **`supabase/migrations/*.sql`** | **Schema notifikasi lengkap** |
| **`supabase/functions/send-push-notification`** | **Edge Function kirim FCM** |
| **`supabase/functions/process-fcm-queue-cron`** | **Edge Function cron processor** |

---

## 🏗️ Build

### Debug Build
```bash
cd rental-android
./gradlew assembleDebug
```

### Release Build
```bash
cd rental-android
./gradlew assembleRelease
```

### Build AAB untuk Play Store
```bash
cd rental-android
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

---

## 🧪 Checklist Testing

- [ ] Aplikasi launch dan memuat URL Akasha Outdoor
- [ ] Push notifications berfungsi (test dari Firebase Console)
- [ ] **FCM token tersimpan ke `user_devices` saat login**
- [ ] **Notifikasi rental.created muncul di admin app**
- [ ] **Notifikasi payment.completed muncul di admin app**
- [ ] **Notifikasi tolerance 2h/1h/30m muncul tepat waktu**
- [ ] **Notifikasi damage.found muncul saat input kerusakan**
- [ ] **Notifikasi profit.shared muncul ke investor**
- [ ] **Notifikasi payout.completed muncul ke investor**
- [ ] Upload kamera berfungsi di form produk
- [ ] Pemilihan multiple file berfungsi
- [ ] Pull-to-refresh berfungsi
- [ ] Halaman offline muncul saat tidak ada internet
- [ ] QR scanner terbuka dan bisa scan kode
- [ ] Biometric auth meminta (jika diimplementasikan di web)
- [ ] Fungsi share berfungsi
- [ ] Deep links terbuka di aplikasi
- [ ] Perilaku tombol back (konfirmasi keluar)
- [ ] Dark mode mengikuti pengaturan sistem
- [ ] Semua permission diminta dengan benar
- [ ] Supabase realtime sinkronisasi data real-time
- [ ] Logo muncul di splash screen dan halaman offline

---

## 🔗 Link Berguna

- [Smart WebView Documentation](https://mgks.github.io/Android-SmartWebView/documentation)
- [Plugin Reference](https://mgks.github.io/Android-SmartWebView/documentation/plugins)
- [Firebase Setup Guide](https://firebase.google.com/docs/android/setup)
- [Play Console](https://play.google.com/console)
- [AdMob Setup](https://developers.google.com/admob/android/quick-start)
- [Akasha Outdoor Admin](https://akasha-outdoor.vercel.app/)
- [Supabase Edge Functions](https://supabase.com/docs/guides/functions)

---

## 📞 Support

Untuk issue Smart WebView: [GitHub Discussions](https://github.com/mgks/Android-SmartWebView/discussions)
Untuk issue Rental Outdoor spesifik: Cek dokumentasi admin panel Anda.

---

## ✅ **RINGKASAN IMPLEMENTASI NOTIFIKASI**

### Phase 1 - Database & Core (✅ Done)
- `user_devices` table dengan RLS multi-tenant
- `fcm_notification_queue` table untuk Edge Function
- Indexes deduping untuk semua event types baru
- `consume_notification_outbox` extended dengan FCM call

### Phase 2 - Triggers (✅ Done)
- `rental.created`, `payment.completed` dari penyewaan/pembayaran
- `damage.found` dari temuan_pemeriksaan
- `extension.*` dari perpanjangan_sewa
- `profit.shared` dari investor_period_distribution
- `payout.completed` dari investor_stakeholder_payout

### Phase 3 - Cron Jobs (✅ Done)
- `generate_rental_deadline_notifications_extended` - tiap menit
- `generate_damage_check_notifications` - tiap 6 jam
- `process_late_fees_scheduled` - harian jam 2 AM
- `consume_notification_outbox` - tiap menit
- `fcm-queue-processor` - tiap menit (panggil Edge Function)

### Phase 4 - Edge Functions (✅ Done)
- `send-push-notification` - FCM HTTP v1 API
- `process-fcm-queue` - Batch processor
- `process-fcm-queue-cron` - Cron wrapper

### Phase 5 - Android Integration (📋 Dokumentasi)
- FCM token saving via JSInterface
- Web admin panel integration guide

---

**Langkah Selanjutnya untuk Anda:**
1. ✅ Set `google-services.json` di `rental-android/app/`
2. ✅ Set Supabase Secrets: `FIREBASE_SERVER_KEY`, `FIREBASE_PROJECT_ID`
3. ✅ Implementasi FCM token saving di web admin panel (kode JS di atas)
4. ✅ Test build debug: `./gradlew assembleDebug`
5. ✅ Test notifikasi end-to-end