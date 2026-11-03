package fr.local.impression;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Calendar;

/**
 * Écrit un travail ESC/P-R (langage raster des jet d'encre Epson).
 *
 * La séquence reproduit octet pour octet celle du pilote Epson officiel
 * (vérifiée sur ET-2600) : en-tête EJL, mode « remote », setq/setj, une
 * commande dsnd par ligne en RGB 24 bits compressé RLE, puis endp/endj.
 */
final class EscprWriter {
    private static final byte ESC = 0x1B;

    private final OutputStream out;
    private byte[] packed = new byte[0];

    EscprWriter(OutputStream out) {
        this.out = out;
    }

    void startJob(Paper paper) throws IOException {
        Calendar now = Calendar.getInstance();
        out.write(new byte[] {0, 0, 0, ESC, 1});
        out.write("@EJL 1284.4\n@EJL     \n".getBytes("US-ASCII"));
        out.write(new byte[] {ESC, '@', ESC, '@'});
        enterRemote();
        out.write(new byte[] {'J', 'S', 4, 0, 0, 0, 0, 0});
        int year = now.get(Calendar.YEAR);
        out.write(new byte[] {'T', 'I', 8, 0, 0, (byte) (year >> 8), (byte) year,
                (byte) (now.get(Calendar.MONTH) + 1), (byte) now.get(Calendar.DAY_OF_MONTH),
                (byte) now.get(Calendar.HOUR_OF_DAY), (byte) now.get(Calendar.MINUTE),
                (byte) now.get(Calendar.SECOND)});
        out.write(new byte[] {'D', 'P', 2, 0, 0, 0}); // recto seul
        exitRemote();
        out.write(new byte[] {ESC, '(', 'R', 6, 0, 0, 'E', 'S', 'C', 'P', 'R'});
        // Papier ordinaire, qualité normale, couleur, réglages neutres, RGB sans palette.
        command('q', "setq", new byte[] {0, 1, 0, 0, 0, 0, 0, 0, 0});
        byte[] job = new byte[22];
        putInt(job, 0, paper.width);
        putInt(job, 4, paper.height);
        putShort(job, 8, Paper.MARGIN);
        putShort(job, 10, Paper.MARGIN);
        putInt(job, 12, paper.areaWidth());
        putInt(job, 16, paper.areaHeight());
        job[20] = 0; // 360 ppp
        job[21] = 0; // sens d'impression
        command('j', "setj", job);
    }

    void startPage() throws IOException {
        command('p', "sttp", new byte[0]);
    }

    /** Une ligne de la zone imprimable, en RGB (3 octets par pixel). */
    void line(int y, byte[] rgb, int pixels) throws IOException {
        if (packed.length < pixels * 3 + pixels / 128 + 2) {
            packed = new byte[pixels * 3 + pixels / 128 + 2];
        }
        int size = runLength(rgb, pixels, packed);
        boolean compressed = size >= 0;
        byte[] data = compressed ? packed : rgb;
        if (!compressed) {
            size = pixels * 3;
        }
        byte[] head = new byte[17];
        head[0] = ESC;
        head[1] = 'd';
        int params = 7 + size;
        head[2] = (byte) params;
        head[3] = (byte) (params >> 8);
        head[4] = (byte) (params >> 16);
        head[5] = (byte) (params >> 24);
        head[6] = 'd';
        head[7] = 's';
        head[8] = 'n';
        head[9] = 'd';
        putShort(head, 10, 0);
        putShort(head, 12, y);
        head[14] = (byte) (compressed ? 1 : 0);
        putShort(head, 15, size);
        out.write(head);
        out.write(data, 0, size);
    }

    void endPage(boolean morePages) throws IOException {
        command('p', "endp", new byte[] {(byte) (morePages ? 1 : 0)});
    }

    void endJob() throws IOException {
        command('j', "endj", new byte[0]);
        out.write(new byte[] {ESC, '@'});
        enterRemote();
        out.write(new byte[] {'L', 'D', 0, 0});
        out.write(new byte[] {'J', 'E', 1, 0, 0});
        exitRemote();
        out.flush();
    }

    private void enterRemote() throws IOException {
        out.write(new byte[] {ESC, '(', 'R', 8, 0, 0, 'R', 'E', 'M', 'O', 'T', 'E', '1'});
    }

    private void exitRemote() throws IOException {
        out.write(new byte[] {ESC, 0, 0, 0});
    }

    private void command(char cls, String name, byte[] params) throws IOException {
        int n = params.length;
        out.write(new byte[] {ESC, (byte) cls, (byte) n, (byte) (n >> 8), (byte) (n >> 16), (byte) (n >> 24)});
        out.write(name.getBytes("US-ASCII"));
        out.write(params);
    }

    /**
     * Compression RLE par pixel, identique à RunLengthEncode() du pilote Epson :
     * 257-n suivi d'un pixel = n répétitions (2..129), n-1 suivi de n pixels = littéraux.
     * Renvoie -1 si la compression ne fait pas gagner de place.
     */
    static int runLength(byte[] src, int pixels, byte[] dst) {
        int limit = pixels * 3;
        int src_i = 0;
        int out = 0;
        int count = 0;
        while (src_i < pixels) {
            if (src_i + 1 < pixels && samePixel(src, src_i, src_i + 1)) {
                int rep = 2;
                while (src_i + rep < pixels && rep < 0x81 && samePixel(src, src_i + rep - 1, src_i + rep)) {
                    rep++;
                }
                count += 4;
                if (count > limit) {
                    return -1;
                }
                dst[out++] = (byte) (0xFF - rep + 2);
                System.arraycopy(src, src_i * 3, dst, out, 3);
                out += 3;
                src_i += rep;
            } else {
                int rep = 1;
                while (src_i + rep + 1 < pixels && rep < 0x80 && !samePixel(src, src_i + rep, src_i + rep + 1)) {
                    rep++;
                }
                count += 1 + rep * 3;
                if (count > limit) {
                    return -1;
                }
                dst[out++] = (byte) (rep - 1);
                System.arraycopy(src, src_i * 3, dst, out, rep * 3);
                out += rep * 3;
                src_i += rep;
            }
        }
        return out;
    }

    private static boolean samePixel(byte[] b, int p, int q) {
        p *= 3;
        q *= 3;
        return b[p] == b[q] && b[p + 1] == b[q + 1] && b[p + 2] == b[q + 2];
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >> 24);
        b[at + 1] = (byte) (v >> 16);
        b[at + 2] = (byte) (v >> 8);
        b[at + 3] = (byte) v;
    }

    private static void putShort(byte[] b, int at, int v) {
        b[at] = (byte) (v >> 8);
        b[at + 1] = (byte) v;
    }
}
