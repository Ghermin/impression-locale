package fr.local.impression;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrinterDiscoverySession;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cherche les imprimantes Epson sur le Wi-Fi via mDNS (service « _pdl-datastream »,
 * c'est-à-dire impression brute sur le port 9100), comme le fait un ordinateur.
 */
final class Discovery extends PrinterDiscoverySession {
    private static final String SERVICE_TYPE = "_pdl-datastream._tcp.";

    private final EpsonPrintService service;
    private final Printers printers;
    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private NsdManager.DiscoveryListener listener;
    private boolean resolving;

    Discovery(EpsonPrintService service) {
        this.service = service;
        this.printers = new Printers(service);
        this.nsd = (NsdManager) service.getSystemService(Context.NSD_SERVICE);
    }

    @Override
    public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
        // Les imprimantes déjà connues apparaissent tout de suite.
        List<PrinterInfo> known = new ArrayList<>();
        for (Printers.Entry e : printers.all()) {
            known.add(service.printerInfo(e));
        }
        addPrinters(known);
        startMdns();
    }

    @Override
    public void onStopPrinterDiscovery() {
        stopMdns();
    }

    @Override
    public void onValidatePrinters(List<PrinterId> printerIds) {
    }

    @Override
    public void onStartPrinterStateTracking(PrinterId printerId) {
        Printers.Entry e = printers.get(printerId.getLocalId());
        if (e != null) {
            List<PrinterInfo> list = new ArrayList<>();
            list.add(service.printerInfo(e));
            addPrinters(list);
        }
    }

    @Override
    public void onStopPrinterStateTracking(PrinterId printerId) {
    }

    @Override
    public void onDestroy() {
        stopMdns();
    }

    private void startMdns() {
        if (listener != null) {
            return;
        }
        listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) {
                Log.i(EpsonPrintService.TAG, "Recherche mDNS lancée");
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> {
                    toResolve.add(info);
                    resolveNext();
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onStartDiscoveryFailed(String type, int errorCode) {
                Log.w(EpsonPrintService.TAG, "Recherche mDNS impossible : " + errorCode);
                main.post(() -> listener = null);
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
            }
        };
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
    }

    private void stopMdns() {
        if (listener != null) {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (IllegalArgumentException ignored) {
                // déjà arrêtée
            }
            listener = null;
        }
        toResolve.clear();
    }

    /** NsdManager ne résout qu'un service à la fois : on les enchaîne. */
    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || toResolve.isEmpty()) {
            return;
        }
        resolving = true;
        nsd.resolveService(toResolve.poll(), new NsdManager.ResolveListener() {
            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                main.post(() -> {
                    resolving = false;
                    onResolved(info);
                    resolveNext();
                });
            }

            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                main.post(() -> {
                    resolving = false;
                    resolveNext();
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void onResolved(NsdServiceInfo info) {
        if (listener == null || !isEpson(info.getAttributes())) {
            return;
        }
        if (!isSupportedModel(info.getAttributes())) {
            Log.i(EpsonPrintService.TAG, "Epson ignorée (modèle sans ESC/P-R) : " + info.getServiceName());
            return;
        }
        InetAddress address = info.getHost();
        if (Build.VERSION.SDK_INT >= 34) {
            for (InetAddress a : info.getHostAddresses()) {
                if (a instanceof Inet4Address) {
                    address = a;
                    break;
                }
            }
        }
        if (address == null) {
            return;
        }
        Printers.Entry entry = new Printers.Entry(info.getServiceName(), info.getServiceName(),
                address.getHostAddress(), info.getPort() > 0 ? info.getPort() : Printers.RAW_PORT);
        printers.save(entry);
        Log.i(EpsonPrintService.TAG, "Imprimante trouvée : " + entry.name + " @ " + entry.host + ":" + entry.port);
        List<PrinterInfo> list = new ArrayList<>();
        list.add(service.printerInfo(entry));
        addPrinters(list);
    }

    private boolean isSupportedModel(Map<String, byte[]> attributes) {
        for (String key : new String[] {"usb_MDL", "ty", "product"}) {
            byte[] value = attributes.get(key);
            if (value != null && SupportedModels.contains(service, new String(value, StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEpson(Map<String, byte[]> attributes) {
        for (String key : new String[] {"usb_MFG", "ty", "product"}) {
            byte[] value = attributes.get(key);
            if (value != null && new String(value, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT).contains("EPSON")) {
                return true;
            }
        }
        return false;
    }
}
