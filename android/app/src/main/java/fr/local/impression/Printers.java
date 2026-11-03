package fr.local.impression;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Imprimantes connues (trouvées sur le réseau ou ajoutées à la main), gardées entre deux lancements. */
final class Printers {
    static final int RAW_PORT = 9100;

    static final class Entry {
        final String id;
        final String name;
        final String host;
        final int port;

        Entry(String id, String name, String host, int port) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private final SharedPreferences prefs;

    Printers(Context context) {
        prefs = context.getSharedPreferences("printers", Context.MODE_PRIVATE);
    }

    void save(Entry e) {
        prefs.edit().putString(e.id, e.name + "\n" + e.host + "\n" + e.port).apply();
    }

    void remove(String id) {
        prefs.edit().remove(id).apply();
    }

    Entry get(String id) {
        return parse(id, prefs.getString(id, null));
    }

    List<Entry> all() {
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Entry entry = parse(e.getKey(), String.valueOf(e.getValue()));
            if (entry != null) {
                list.add(entry);
            }
        }
        return list;
    }

    private static Entry parse(String id, String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\n");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new Entry(id, parts[0], parts[1], Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
