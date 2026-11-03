package fr.local.impression;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Regarde au plus une fois par jour s'il existe une version plus récente sur GitHub.
 * C'est la seule connexion de l'appli en dehors du réseau local : elle ne
 * transmet rien, elle lit seulement la dernière version publiée.
 */
final class UpdateChecker {
    static final String REPO = "Ghermin/impression-locale";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOAD = "https://github.com/" + REPO + "/releases/latest/download/ImpressionLocale.apk";
    private static final long DAY = 24 * 60 * 60 * 1000L;
    private static final String CHANNEL = "maj";

    private static final Executor executor = Executors.newSingleThreadExecutor();

    interface Listener {
        void onResult();
    }

    /** Lance une vérification si la dernière date de plus d'un jour (ou toujours si force). */
    static void check(Context context, boolean force, Listener listener) {
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
                JSONArray assets = release.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.getString("browser_download_url");
                    }
                }
                prefs.edit().putString("latest", latest).putString("url", url).apply();
                if (isNewer(latest, installedVersion(app)) && !latest.equals(prefs.getString("notified", ""))
                        && notify(app, latest, url)) {
                    prefs.edit().putString("notified", latest).apply();
                }
                Log.i(EpsonPrintService.TAG, "Dernière version publiée : " + latest);
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

    /** Renvoie false si les notifications sont refusées : on réessaiera à la prochaine vérification. */
    private static boolean notify(Context context, String version, String url) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) {
            return false;
        }
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Mises à jour",
                NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_printer)
                .setContentTitle("Impression locale " + version + " disponible")
                .setContentText("Touchez pour télécharger la mise à jour, puis ouvrez le fichier.")
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
}
