package fr.local.impression;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintJobId;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service d'impression Android pour les jet d'encre Epson ESC/P-R.
 * Les pages sont rendues sur le téléphone et envoyées directement à
 * l'imprimante (port 9100) : rien ne sort du réseau local.
 */
public class EpsonPrintService extends PrintService {
    static final String TAG = "ImpressionLocale";
    private static final int BAND = 128;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<PrintJobId> cancelled = ConcurrentHashMap.newKeySet();

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new Discovery(this);
    }

    PrinterInfo printerInfo(Printers.Entry entry) {
        PrinterId id = generatePrinterId(entry.id);
        PrinterCapabilitiesInfo.Builder caps = new PrinterCapabilitiesInfo.Builder(id);
        for (int i = 0; i < Paper.ALL.length; i++) {
            caps.addMediaSize(Paper.ALL[i].media, i == 0);
        }
        caps.addResolution(new PrintAttributes.Resolution("360", "360 ppp", 360, 360), true);
        caps.setColorModes(PrintAttributes.COLOR_MODE_COLOR | PrintAttributes.COLOR_MODE_MONOCHROME,
                PrintAttributes.COLOR_MODE_COLOR);
        int m = Paper.MARGIN_MILS;
        caps.setMinMargins(new PrintAttributes.Margins(m, m, m, m));
        return new PrinterInfo.Builder(id, entry.name, PrinterInfo.STATUS_IDLE)
                .setDescription(entry.host)
                .setCapabilities(caps.build())
                .build();
    }

    @Override
    protected void onPrintJobQueued(PrintJob job) {
        Printers.Entry printer = new Printers(this).get(job.getInfo().getPrinterId().getLocalId());
        ParcelFileDescriptor data = job.getDocument().getData();
        if (printer == null || data == null) {
            job.fail(printer == null ? "Imprimante inconnue" : "Document illisible");
            return;
        }
        PrintAttributes attrs = job.getInfo().getAttributes();
        int copies = Math.max(1, job.getInfo().getCopies());
        PrintJobId jobId = job.getId();
        job.start();
        worker.execute(() -> {
            String error = null;
            try {
                print(printer, data, attrs, copies, jobId);
            } catch (Exception e) {
                Log.e(TAG, "Échec de l'impression", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String failure = error;
            main.post(() -> {
                if (cancelled.remove(jobId)) {
                    job.cancel();
                } else if (failure != null) {
                    job.fail("Imprimante injoignable ou erreur : " + failure);
                } else {
                    job.complete();
                }
            });
        });
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob job) {
        if (job.isQueued()) {
            job.cancel();
        } else {
            cancelled.add(job.getId());
        }
    }

    private void print(Printers.Entry printer, ParcelFileDescriptor data, PrintAttributes attrs,
                       int copies, PrintJobId jobId) throws IOException {
        File pdf = File.createTempFile("travail", ".pdf", getCacheDir());
        try {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(data);
                 OutputStream out = new FileOutputStream(pdf)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
            }
            Paper paper = Paper.forMedia(attrs.getMediaSize());
            boolean mono = attrs.getColorMode() == PrintAttributes.COLOR_MODE_MONOCHROME;
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(fd);
                 Socket socket = new Socket()) {
                int pages = renderer.getPageCount();
                Log.i(TAG, "Impression de " + pages + " page(s) x" + copies + " sur " + printer.host
                        + " (" + paper.media.getId() + (mono ? ", noir et blanc" : "") + ")");
                socket.connect(new InetSocketAddress(printer.host, printer.port), 10_000);
                socket.setSoTimeout(120_000);
                EscprWriter writer = new EscprWriter(new BufferedOutputStream(socket.getOutputStream(), 1 << 16));
                writer.startJob(paper);
                int total = pages * copies;
                int done = 0;
                boolean stopped = false;
                for (int c = 0; c < copies && !stopped; c++) {
                    for (int p = 0; p < pages && !stopped; p++) {
                        writer.startPage();
                        stopped = !renderPage(renderer, p, paper, mono, writer, jobId);
                        done++;
                        // Même annulé, on termine proprement la page et le travail :
                        // un flux coupé net laisse l'imprimante bloquée.
                        writer.endPage(!stopped && done < total);
                    }
                }
                writer.endJob();
                socket.shutdownOutput();
                Log.i(TAG, stopped ? "Travail annulé" : "Travail envoyé");
            }
        } finally {
            pdf.delete();
        }
    }

    /** Envoie une page ; renvoie false si le travail a été annulé en cours de route. */
    private boolean renderPage(PdfRenderer renderer, int index, Paper paper, boolean mono,
                               EscprWriter writer, PrintJobId jobId) throws IOException {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            float pw = page.getWidth();
            float ph = page.getHeight();
            boolean rotate = (pw > ph) != (paper.width > paper.height);
            float contentW = rotate ? ph : pw;
            float contentH = rotate ? pw : ph;
            float scale = Math.min(paper.width / contentW, paper.height / contentH);

            // Points PDF -> pixels de la zone imprimable (feuille entière décalée de la marge).
            Matrix base = new Matrix();
            base.setScale(scale, scale);
            if (rotate) { // paysage : haut de la page à gauche de la feuille
                base.postRotate(-90);
                base.postTranslate(0, pw * scale);
            }
            base.postTranslate((paper.width - contentW * scale) / 2f - Paper.MARGIN,
                    (paper.height - contentH * scale) / 2f - Paper.MARGIN);

            int width = paper.areaWidth();
            int height = paper.areaHeight();
            Bitmap band = Bitmap.createBitmap(width, BAND, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[width * BAND];
            byte[] rgb = new byte[width * 3];
            Matrix m = new Matrix();
            try {
                for (int top = 0; top < height; top += BAND) {
                    if (cancelled.contains(jobId)) {
                        return false;
                    }
                    int rows = Math.min(BAND, height - top);
                    band.eraseColor(Color.WHITE);
                    m.set(base);
                    m.postTranslate(0, -top);
                    page.render(band, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    band.getPixels(pixels, 0, width, 0, 0, width, rows);
                    for (int r = 0; r < rows; r++) {
                        int row = r * width;
                        for (int x = 0; x < width; x++) {
                            int c = pixels[row + x];
                            int red = (c >> 16) & 0xFF;
                            int green = (c >> 8) & 0xFF;
                            int blue = c & 0xFF;
                            if (mono) {
                                red = green = blue = (red * 299 + green * 587 + blue * 114) / 1000;
                            }
                            rgb[x * 3] = (byte) red;
                            rgb[x * 3 + 1] = (byte) green;
                            rgb[x * 3 + 2] = (byte) blue;
                        }
                        writer.line(top + r, rgb, width);
                    }
                }
            } finally {
                band.recycle();
            }
            return true;
        }
    }

    @Override
    public void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
