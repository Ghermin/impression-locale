package fr.local.impression;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.print.pdf.PrintedPdfDocument;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.regex.Pattern;

/** Écran d'accueil : imprimantes connues, ajout par adresse IP, page de test. */
public class MainActivity extends Activity {
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    private Printers printers;
    private TextView list;
    private Button update;
    private TextView version;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        printers = new Printers(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = text("Impression locale", 22);
        root.addView(title);

        update = new Button(this);
        update.setVisibility(View.GONE);
        update.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse(UpdateChecker.downloadUrl(this)))));
        root.addView(update);
        root.addView(text("Imprime sur les Epson de ton Wi-Fi directement depuis le téléphone, "
                + "sans appli Epson et sans que tes documents passent par Internet. Dans n'importe quelle appli : "
                + "Partager / ⋮ > Imprimer, puis choisis l'imprimante.", 15));

        root.addView(text("Imprimantes connues", 18));
        list = text("", 15);
        root.addView(list);

        Button test = new Button(this);
        test.setText("Imprimer une page de test");
        test.setOnClickListener(v -> printTestPage());
        root.addView(test);

        root.addView(text("Seuls les jet d'encre Epson compatibles ESC/P-R sont proposés "
                + "(EcoTank, Expression, WorkForce… environ 600 modèles). Si la tienne n'apparaît pas "
                + "alors qu'elle est compatible, ajoute son adresse IP (visible sur sa feuille d'état réseau) :", 15));
        EditText ip = new EditText(this);
        ip.setHint("192.168.1.50");
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(ip);
        Button add = new Button(this);
        add.setText("Ajouter cette adresse");
        add.setOnClickListener(v -> {
            String host = ip.getText().toString().trim();
            if (!IPV4.matcher(host).matches()) {
                Toast.makeText(this, "Adresse IP invalide", Toast.LENGTH_SHORT).show();
                return;
            }
            printers.save(new Printers.Entry("ip:" + host, "Epson (" + host + ")", host, Printers.RAW_PORT));
            ip.setText("");
            refresh();
        });
        root.addView(add);

        Button settings = new Button(this);
        settings.setText("Réglages d'impression d'Android");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_PRINT_SETTINGS)));
        root.addView(settings);

        version = text("", 13);
        root.addView(version);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        // Affichage bord à bord (Android 15+) : on garde le texte hors des barres système.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (!night && Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        refreshUpdate();
        UpdateChecker.check(this, true, () -> runOnUiThread(this::refreshUpdate));
    }

    private void refreshUpdate() {
        String latest = UpdateChecker.availableUpdate(this);
        update.setVisibility(latest != null ? View.VISIBLE : View.GONE);
        if (latest != null) {
            update.setText("Mise à jour " + latest + " disponible : télécharger");
        }
        version.setText("Version " + UpdateChecker.installedVersion(this)
                + (latest == null ? " · à jour" : "") + " · github.com/" + UpdateChecker.REPO);
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        for (Printers.Entry e : printers.all()) {
            sb.append("• ").append(e.name).append(" — ").append(e.host).append('\n');
        }
        list.setText(sb.length() > 0 ? sb.toString().trim()
                : "Aucune pour l'instant : elles apparaissent quand tu ouvres l'écran d'impression.");
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(0, dp(8), 0, dp(8));
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void printTestPage() {
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        pm.print("Page de test", new TestPage(), new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
    }

    /** Une page avec cadre, texte, couleurs primaires et dégradé de gris. */
    private class TestPage extends PrintDocumentAdapter {
        private PrintAttributes attributes;

        @Override
        public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                             CancellationSignal cancel, LayoutResultCallback callback, Bundle extras) {
            attributes = newAttributes;
            callback.onLayoutFinished(new PrintDocumentInfo.Builder("page-de-test.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build(), true);
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                            CancellationSignal cancel, WriteResultCallback callback) {
            PrintedPdfDocument doc = new PrintedPdfDocument(MainActivity.this, attributes);
            PdfDocument.Page page = doc.startPage(0);
            // Le canevas a déjà pour origine le coin de la zone de contenu.
            Rect content = page.getInfo().getContentRect();
            draw(page.getCanvas(), new Rect(1, 1, content.width() - 1, content.height() - 1));
            doc.finishPage(page);
            try (FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                doc.writeTo(out);
                callback.onWriteFinished(new PageRange[] {PageRange.ALL_PAGES});
            } catch (IOException e) {
                callback.onWriteFailed(e.getMessage());
            } finally {
                doc.close();
            }
        }

        private void draw(Canvas c, Rect r) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            c.drawRect(r, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(22);
            c.drawText("Impression locale — page de test", r.left + 24, r.top + 44, p);
            p.setTextSize(12);
            c.drawText(DateFormat.getDateTimeInstance().format(new Date()), r.left + 24, r.top + 66, p);
            int[] colors = {Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.RED, Color.rgb(0, 160, 0), Color.BLUE, Color.BLACK};
            float w = (r.width() - 48) / (float) colors.length;
            for (int i = 0; i < colors.length; i++) {
                p.setColor(colors[i]);
                c.drawRect(r.left + 24 + i * w, r.top + 90, r.left + 24 + (i + 1) * w - 6, r.top + 150, p);
            }
            for (int i = 0; i < 64; i++) {
                int g = 255 - i * 4;
                p.setColor(Color.rgb(g, g, g));
                float x = r.left + 24 + i * (r.width() - 48) / 64f;
                c.drawRect(x, r.top + 170, x + (r.width() - 48) / 64f + 1, r.top + 200, p);
            }
            p.setColor(Color.rgb(200, 0, 0));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            c.drawCircle(r.centerX(), r.centerY() + 40, Math.min(r.width(), r.height()) / 4f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.BLACK);
            p.setTextSize(11);
            c.drawText("Si les couleurs, le cadre et le cercle sont nets, tout fonctionne.",
                    r.left + 24, r.bottom - 24, p);
        }
    }
}
