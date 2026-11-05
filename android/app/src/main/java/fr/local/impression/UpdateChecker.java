package fr.local.impression;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Mise à jour automatique depuis la dernière release GitHub, comme CamTel et Averse :
 * à l'ouverture de l'appli, si une version plus récente existe, elle est téléchargée
 * puis installée. Quand l'appli s'est installée elle-même (ou que le PC l'a déclarée
 * comme installateur par USB), Android l'applique sans rien demander ; sinon il
 * affiche une seule confirmation.
 *
 * C'est la seule connexion de l'appli en dehors du réseau local : elle ne transmet
 * rien, elle lit seulement la dernière version publiée.
 */
final class UpdateChecker {
    static final String REPO = "Ghermin/impression-locale";
    static final String APK = "ImpressionLocale.apk";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOAD = "https://github.com/" + REPO + "/releases/latest/download/" + APK;
    private static final long DAY = 24 * 60 * 60 * 1000L;
    private static final String CHANNEL = "maj";

    private static final Executor executor = Executors.newSingleThreadExecutor();
    private static volatile boolean installing;

    interface Listener {
        void onResult();
    }

    /**
     * Vérifie la dernière version (au plus une fois par jour, sauf si force). Avec install,
     * une version plus récente est aussitôt téléchargée et installée ; sinon on prévient
     * seulement par une notification (depuis le service d'impression, en arrière-plan).
     */
    static void check(Context context, boolean force, boolean install, Listener listener) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        if (!force && System.currentTimeMillis() - prefs.getLong("checked", 0) < DAY) {
            return;
        }
        prefs.edit().putLong("checked", System.currentTimeMillis()).apply();
        executor.execute(() -> {
            try {
                JSONObject release = new JSONObject(get(LATEST));
                String latest = release.getString("tag_name").replaceFirst("^[vV]", "");
                String url = DOWNLOAD;
                long size = -1;
                JSONArray assets = release.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.getString("browser_download_url");
                        size = a.optLong("size", -1);
                    }
                }
                prefs.edit().putString("latest", latest).putString("url", url).apply();
                Log.i(EpsonPrintService.TAG, "Dernière version publiée : " + latest);
                if (isNewer(latest, installedVersion(app))) {
                    if (install && !BuildConfig.DEBUG) {
                        if (listener != null) {
                            listener.onResult();
                        }
                        downloadAndInstall(app, latest, url, size);
                    } else if (!latest.equals(prefs.getString("notified", "")) && notify(app, latest)) {
                        prefs.edit().putString("notified", latest).apply();
                    }
                }
            } catch (Exception e) {
                Log.i(EpsonPrintService.TAG, "Vérification des mises à jour impossible : " + e);
            }
            if (listener != null) {
                listener.onResult();
            }
        });
    }

    /** Version plus récente déjà connue, ou null si l'appli est à jour. */
    static String availableUpdate(Context context) {
        String latest = prefs(context).getString("latest", null);
        return latest != null && isNewer(latest, installedVersion(context)) ? latest : null;
    }

    static boolean isInstalling() {
        return installing;
    }

    static String downloadUrl(Context context) {
        return prefs(context).getString("url", DOWNLOAD);
    }

    static String installedVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Compare « 1.10 » et « 1.9 » nombre par nombre. */
    static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("[^0-9]+");
        String[] b = current.split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static void downloadAndInstall(Context app, String version, String url, long size) {
        if (installing) {
            return;
        }
        installing = true;
        try {
            toast(app, "Mise à jour d'Impression locale vers " + version + "…");
            File apk = new File(app.getCacheDir(), "update.apk");
            download(url, apk);
            if (size > 0 && apk.length() != size) {
                throw new IllegalStateException("téléchargement incomplet (" + apk.length() + " / " + size + ")");
            }
            prefs(app).edit().putString("updatingTo", version).apply();
            install(app, apk);
        } catch (Exception e) {
            installing = false;
            Log.w(EpsonPrintService.TAG, "Mise à jour impossible", e);
        }
    }

    private static void download(String url, File target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void install(Context context, File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite(APK, 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(context, id,
                    new Intent(context, InstallResult.class), flags);
            session.commit(pending.getIntentSender());
        }
    }

    /** Affiche la confirmation système quand Android en exige une (première mise à jour). */
    public static final class InstallResult extends BroadcastReceiver {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                installing = false;
                Log.w(EpsonPrintService.TAG, "Installation refusée : "
                        + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                prefs(context).edit().remove("updatingTo").apply();
            }
        }
    }

    /** Après une mise à jour l'appli est fermée : on propose de la rouvrir. */
    public static final class Updated extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                return;
            }
            SharedPreferences prefs = prefs(context);
            if (prefs.getString("updatingTo", null) == null) {
                return; // installée par le PC (USB) : rien à signaler
            }
            prefs.edit().remove("updatingTo").apply();
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            createChannel(nm);
            PendingIntent open = PendingIntent.getActivity(context, 0,
                    new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_printer)
                    .setContentTitle("Impression locale mise à jour (" + installedVersion(context) + ")")
                    .setContentText("Touchez pour rouvrir l'appli")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            try {
                nm.notify(2, n);
            } catch (SecurityException ignored) {
                // notifications refusées
            }
        }
    }

    /** Renvoie false si les notifications sont refusées : on réessaiera à la prochaine vérification. */
    private static boolean notify(Context context, String version) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) {
            return false;
        }
        createChannel(nm);
        // Ouvrir l'appli suffit : elle installe la mise à jour elle-même.
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_printer)
                .setContentTitle("Impression locale " + version + " disponible")
                .setContentText("Touchez pour ouvrir l'appli : la mise à jour s'installe toute seule.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(1, n);
            return true;
        } catch (SecurityException e) {
            return false; // la mise à jour reste signalée dans l'appli
        }
    }

    private static void createChannel(NotificationManager nm) {
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Mises à jour",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static void toast(Context context, String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("maj", Context.MODE_PRIVATE);
    }

    /** Pour le bouton de secours : ouvre la page de téléchargement dans le navigateur. */
    static Intent browserIntent(Context context) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl(context)));
    }
}
