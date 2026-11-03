package fr.local.impression;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Modèles qui comprennent l'ESC/P-R, d'après les fichiers PPD du pilote Linux
 * d'Epson (assets/escpr_models.txt). Les autres Epson (ESC/P-R2 seul, laser)
 * ne doivent pas recevoir ce flux : un travail incompris peut bloquer l'imprimante.
 */
final class SupportedModels {
    private static Set<String> models;

    static synchronized boolean contains(Context context, String model) {
        if (models == null) {
            models = load(context);
        }
        return model != null && models.contains(normalize(model));
    }

    /** « EPSON ET-2600 Series » et « (ET-2600 Series) » deviennent « ET2600 ». */
    static String normalize(String model) {
        return model.toUpperCase(Locale.ROOT)
                .replace("EPSON", "")
                .replace("SERIES", "")
                .replaceAll("[^A-Z0-9]", "");
    }

    private static Set<String> load(Context context) {
        Set<String> set = new HashSet<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                context.getAssets().open("escpr_models.txt"), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (!line.isBlank()) {
                    set.add(normalize(line));
                }
            }
        } catch (IOException e) {
            Log.e(EpsonPrintService.TAG, "Liste des modèles illisible", e);
        }
        return set;
    }
}
