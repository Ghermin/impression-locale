package fr.local.impression;

import android.print.PrintAttributes.MediaSize;

/** Formats acceptés par l'ET-2600, en pixels à 360 ppp (table du pilote Epson). */
final class Paper {
    /** Marge de 3 mm imposée en mode avec bordures, en pixels à 360 ppp. */
    static final int MARGIN = 42;
    static final int MARGIN_MILS = 117;

    static final Paper[] ALL = {
        new Paper(MediaSize.ISO_A4, 2976, 4209),
        new Paper(MediaSize.NA_LETTER, 3060, 3960),
        new Paper(MediaSize.NA_LEGAL, 3060, 5040),
        new Paper(MediaSize.ISO_A5, 2098, 2976),
        new Paper(MediaSize.ISO_A6, 1488, 2098),
        new Paper(MediaSize.JIS_B5, 2580, 3643),
        new Paper(MediaSize.NA_INDEX_4X6, 1440, 2160),
        new Paper(new MediaSize("EPSON_2L", "13 x 18 cm (5 x 7 in)", 5000, 7000), 1800, 2522),
        new Paper(MediaSize.JPN_HAGAKI, 1417, 2098),
    };

    final MediaSize media;
    final int width;
    final int height;

    private Paper(MediaSize media, int width, int height) {
        this.media = media;
        this.width = width;
        this.height = height;
    }

    int areaWidth() {
        return width - 2 * MARGIN;
    }

    int areaHeight() {
        return height - 2 * MARGIN;
    }

    static Paper forMedia(MediaSize media) {
        if (media != null) {
            for (Paper p : ALL) {
                if (p.media.getId().equals(media.getId())) {
                    return p;
                }
            }
            // Format inconnu : le plus proche en taille.
            Paper best = ALL[0];
            int bestDiff = Integer.MAX_VALUE;
            MediaSize portrait = media.asPortrait();
            for (Paper p : ALL) {
                int diff = Math.abs(p.media.getWidthMils() - portrait.getWidthMils())
                        + Math.abs(p.media.getHeightMils() - portrait.getHeightMils());
                if (diff < bestDiff) {
                    best = p;
                    bestDiff = diff;
                }
            }
            return best;
        }
        return ALL[0];
    }
}
