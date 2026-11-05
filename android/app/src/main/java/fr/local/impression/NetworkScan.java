package fr.local.impression;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Recherche active des imprimantes à l'ouverture de l'appli : on sonde tout le
 * sous-réseau Wi-Fi sur le port d'impression brute (9100), puis on demande son
 * modèle à chaque appareil qui répond (SNMP, comme le fait Windows). Complète la
 * recherche mDNS du service d'impression, qui rate les imprimantes dont le
 * Bonjour est coupé ou filtré par le routeur.
 *
 * Tout reste sur le réseau local : aucune adresse n'est contactée en dehors.
 */
final class NetworkScan {
    interface Listener {
        /** Appelé sur le fil principal. */
        void onStatus(String status, boolean done);
    }

    private static final int CONNECT_TIMEOUT_MS = 600;
    private static final int SNMP_TIMEOUT_MS = 1500;
    private static final int THREADS = 64;
    /** hrDeviceDescr.1 : « EPSON ET-2810 Series ». */
    private static final int[] OID_DEVICE = {1, 3, 6, 1, 2, 1, 25, 3, 2, 1, 3, 1};
    /** sysDescr.0 : « EPSON Built-in 11b/g/n Print Server ». */
    private static final int[] OID_SYSTEM = {1, 3, 6, 1, 2, 1, 1, 1, 0};

    private static volatile boolean running;

    private NetworkScan() {
    }

    static boolean isRunning() {
        return running;
    }

    static void start(Context context, Listener listener) {
        if (running) {
            return;
        }
        running = true;
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String result;
            try {
                result = scan(app, status -> main.post(() -> listener.onStatus(status, false)));
            } catch (Exception e) {
                Log.w(EpsonPrintService.TAG, "Recherche réseau impossible", e);
                result = "Recherche impossible : " + e.getMessage();
            }
            running = false;
            String r = result;
            main.post(() -> listener.onStatus(r, true));
        }, "recherche-imprimantes").start();
    }

    private interface Progress {
        void report(String status);
    }

    private static String scan(Context context, Progress progress) throws Exception {
        Subnet subnet = localSubnet(context);
        if (subnet == null) {
            return "Pas de Wi-Fi : connecte le téléphone au même réseau que l'imprimante.";
        }
        progress.report("Recherche des imprimantes sur " + subnet + "…");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> probes = new ArrayList<>();
        for (int host = subnet.first; host <= subnet.last; host++) {
            if (host == subnet.self) {
                continue;
            }
            String ip = toIp(host);
            probes.add(pool.submit(() -> isOpen(ip, Printers.RAW_PORT) ? ip : null));
        }
        List<String> open = Collections.synchronizedList(new ArrayList<>());
        for (Future<String> f : probes) {
            String ip = f.get();
            if (ip != null) {
                open.add(ip);
            }
        }

        Printers printers = new Printers(context);
        List<Future<String>> identified = new ArrayList<>();
        for (String ip : open) {
            identified.add(pool.submit(() -> identify(context, printers, ip)));
        }
        List<String> found = new ArrayList<>();
        for (Future<String> f : identified) {
            String name = f.get();
            if (name != null) {
                found.add(name);
            }
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        if (found.isEmpty()) {
            return open.isEmpty()
                    ? "Aucune imprimante trouvée sur le réseau. Vérifie qu'elle est allumée et sur le même Wi-Fi."
                    : "Aucune Epson compatible parmi les " + open.size() + " imprimante(s) du réseau.";
        }
        return found.size() == 1 ? "Imprimante trouvée : " + found.get(0)
                : found.size() + " imprimantes trouvées.";
    }

    /** Renvoie le nom enregistré, ou null si l'appareil n'est pas une Epson utilisable. */
    private static String identify(Context context, Printers printers, String ip) {
        String model = snmpString(ip, OID_DEVICE);
        String system = model == null ? snmpString(ip, OID_SYSTEM) : null;
        String name;
        if (model != null) {
            if (!model.toUpperCase(Locale.ROOT).contains("EPSON")) {
                Log.i(EpsonPrintService.TAG, "Pas une Epson : " + model + " @ " + ip);
                return null;
            }
            if (!SupportedModels.contains(context, model)) {
                Log.i(EpsonPrintService.TAG, "Epson ignorée (modèle sans ESC/P-R) : " + model + " @ " + ip);
                return null;
            }
            name = model.trim();
        } else if ((system != null && system.toUpperCase(Locale.ROOT).contains("EPSON")) || httpServerIsEpson(ip)) {
            // Modèle inconnu (SNMP coupé) : on la propose comme un ajout manuel par adresse.
            name = "Epson (" + ip + ")";
        } else {
            return null;
        }
        Printers.Entry saved = printers.save(new Printers.Entry("ip:" + ip, name, ip, Printers.RAW_PORT));
        Log.i(EpsonPrintService.TAG, "Recherche réseau : " + saved.name + " @ " + ip);
        return saved.name;
    }

    private static boolean isOpen(String ip, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean httpServerIsEpson(String ip) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, 80), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write(("HEAD / HTTP/1.0\r\nHost: " + ip + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            for (int n; head.size() < 4096 && (n = in.read(buf)) > 0; ) {
                head.write(buf, 0, n);
            }
            for (String line : head.toString(StandardCharsets.ISO_8859_1.name()).split("\r?\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("server:")
                        && line.toUpperCase(Locale.ROOT).contains("EPSON")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // pas de serveur web
        }
        return false;
    }

    // --- SNMP v1, communauté « public » : une seule requête GET, réponse en BER ---

    static String snmpString(String ip, int[] oid) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(SNMP_TIMEOUT_MS);
            byte[] request = snmpGet(oid);
            InetAddress address = InetAddress.getByName(ip);
            byte[] buf = new byte[1500];
            for (int attempt = 0; attempt < 2; attempt++) {
                socket.send(new DatagramPacket(request, request.length, address, 161));
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(reply);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                List<Object[]> values = new ArrayList<>();
                primitives(buf, 0, reply.getLength(), values);
                // La valeur demandée est la dernière : une chaîne, ou NULL / noSuchObject si absente.
                Object[] last = values.isEmpty() ? null : values.get(values.size() - 1);
                if (last == null || (int) last[0] != 0x04) {
                    return null;
                }
                String value = (String) last[1];
                return value.isBlank() ? null : value.trim();
            }
        } catch (Exception ignored) {
            // SNMP coupé ou appareil muet
        }
        return null;
    }

    private static byte[] snmpGet(int[] oid) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(oid[0] * 40 + oid[1]);
        for (int i = 2; i < oid.length; i++) {
            int v = oid[i];
            if (v >= 128) {
                o.write(0x80 | (v >> 7));
            }
            o.write(v & 0x7f);
        }
        byte[] varBind = tlv(0x30, concat(tlv(0x06, o.toByteArray()), new byte[] {0x05, 0x00}));
        byte[] pdu = tlv(0xa0, concat(tlv(0x02, new byte[] {0x01}), tlv(0x02, new byte[] {0}),
                tlv(0x02, new byte[] {0}), tlv(0x30, varBind)));
        return tlv(0x30, concat(tlv(0x02, new byte[] {0}),
                tlv(0x04, "public".getBytes(StandardCharsets.US_ASCII)), pdu));
    }

    /** Aplatit la réponse BER en liste {tag, texte} des éléments simples, dans l'ordre. */
    private static void primitives(byte[] b, int start, int end, List<Object[]> out) {
        int i = start;
        while (i + 1 < end) {
            int tag = b[i] & 0xff;
            int len = b[i + 1] & 0xff;
            int p = i + 2;
            if (len > 0x80) {
                int n = len & 0x7f;
                len = 0;
                for (int k = 0; k < n && p < end; k++) {
                    len = (len << 8) | (b[p++] & 0xff);
                }
            }
            if (p + len > end) {
                break;
            }
            if ((tag & 0x20) != 0) {
                primitives(b, p, p + len, out);
            } else {
                out.add(new Object[] {tag, new String(b, p, len, StandardCharsets.UTF_8)});
            }
            i = p + len;
        }
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (value.length < 128) {
            o.write(value.length);
        } else {
            o.write(0x82);
            o.write(value.length >> 8);
            o.write(value.length & 0xff);
        }
        o.write(value, 0, value.length);
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    // --- Sous-réseau du Wi-Fi ---

    private static final class Subnet {
        final int first;
        final int last;
        final int self;
        final String label;

        Subnet(int first, int last, int self, String label) {
            this.first = first;
            this.last = last;
            this.self = self;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Sous-réseau IPv4 du Wi-Fi (ou Ethernet), limité à 254 adresses autour du téléphone. */
    private static Subnet localSubnet(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                continue;
            }
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) {
                continue;
            }
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (!(la.getAddress() instanceof Inet4Address) || la.getAddress().isLoopbackAddress()) {
                    continue;
                }
                int self = toInt(la.getAddress().getAddress());
                int prefix = Math.max(la.getPrefixLength(), 24);
                if (prefix >= 31) {
                    continue;
                }
                int mask = -1 << (32 - prefix);
                int network32 = self & mask;
                int broadcast = network32 | ~mask;
                return new Subnet(network32 + 1, broadcast - 1, self, toIp(network32) + "/" + prefix);
            }
        }
        return null;
    }

    private static int toInt(byte[] a) {
        return ((a[0] & 0xff) << 24) | ((a[1] & 0xff) << 16) | ((a[2] & 0xff) << 8) | (a[3] & 0xff);
    }

    private static String toIp(int v) {
        return ((v >>> 24) & 0xff) + "." + ((v >>> 16) & 0xff) + "." + ((v >>> 8) & 0xff) + "." + (v & 0xff);
    }
}
