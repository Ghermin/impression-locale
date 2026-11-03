# Impression locale

**Imprimer sur une jet d'encre Epson depuis Android, sans appli Epson et sans passer par Internet.**
*English below.*

Site : https://ghermin.github.io/impression-locale/ — Télécharger : [ImpressionLocale.apk](https://github.com/Ghermin/impression-locale/releases/latest/download/ImpressionLocale.apk)

C'est un **plug-in d'impression Android**, comme Epson Print Enabler : on l'installe une
fois, puis l'imprimante apparaît dans le menu **Imprimer** natif de toutes les applis
(Partager / ⋮ > Imprimer). Pas besoin d'ouvrir l'appli.

- Le téléphone convertit lui-même les pages dans le langage des jet d'encre Epson
  (ESC/P-R) et les envoie **directement à l'imprimante** sur le Wi-Fi (port 9100).
- **Rien ne sort du réseau local** : pas de compte, pas de statistiques, pas de serveur,
  pas de pub. Le code fait ~1000 lignes, tout est lisible dans [android/](android/).
- L'imprimante est trouvée automatiquement (mDNS/Bonjour). Sinon, on peut ajouter son
  adresse IP dans l'appli « Impression locale ».
- Formats : A4, Lettre, Légal, A5, A6, B5, 10×15, 13×18, carte postale ; couleur ou
  noir et blanc ; copies multiples. Android 8 ou plus récent.

## Mon imprimante est-elle compatible ?

Il faut une **jet d'encre Epson qui comprend l'ESC/P-R**, connectée au Wi-Fi. Cela
concerne les 617 modèles du pilote Linux officiel d'Epson (EcoTank ET/L, Expression,
WorkForce, Stylus…) : voir la [liste](android/app/src/main/assets/escpr_models.txt) ou
le moteur de recherche sur le [site](https://ghermin.github.io/impression-locale/).

Ne fonctionne **pas** avec : les modèles récents qui ne parlent que l'ESC/P-R2, les
laser Epson, les imprimantes branchées uniquement en USB. L'appli n'affiche que les
modèles de la liste.

> Testé en conditions réelles uniquement sur une **EcoTank ET-2600** avec un Pixel 8a
> (Android 17). Les autres modèles utilisent le même langage mais n'ont pas été essayés :
> les retours sont bienvenus dans les [issues](https://github.com/Ghermin/impression-locale/issues).

## Installation

1. Télécharger [ImpressionLocale.apk](https://github.com/Ghermin/impression-locale/releases/latest/download/ImpressionLocale.apk)
   sur le téléphone et l'ouvrir (autoriser l'installation d'applis inconnues pour le
   navigateur ou le gestionnaire de fichiers).
2. Vérifier dans Paramètres > Appareils connectés > Préférences de connexion >
   **Impression** que « Impression locale » est activé.
3. Optionnel : désinstaller Epson Print Enabler / Epson iPrint pour ne garder qu'une
   seule entrée par imprimante.
4. Dans l'appli « Impression locale », **Imprimer une page de test**.

## Limites

- Qualité « normale » 360 ppp sur papier ordinaire ; pas de sans-bordure ni de recto
  verso automatique ; pas d'affichage des niveaux d'encre.
- Si l'imprimante reçoit un travail incomplet (Wi-Fi coupé en pleine impression), elle
  peut rester bloquée avec un voyant qui clignote : l'éteindre et la rallumer.

## Compiler

```
cd android
./gradlew assembleRelease     # Windows : gradlew.bat assembleRelease
```

APK : `android/app/build/outputs/apk/release/app-release.apk` (JDK 17 et SDK Android 35).

## Comment ça marche

| Fichier | Rôle |
|---|---|
| [Discovery.java](android/app/src/main/java/fr/local/impression/Discovery.java) | Cherche `_pdl-datastream._tcp` en mDNS, garde les Epson ESC/P-R |
| [EpsonPrintService.java](android/app/src/main/java/fr/local/impression/EpsonPrintService.java) | Service d'impression Android : rend chaque page du PDF par bandes à 360 ppp et les envoie |
| [EscprWriter.java](android/app/src/main/java/fr/local/impression/EscprWriter.java) | Écrit le flux ESC/P-R (en-tête, lignes RGB compressées RLE, fin de travail) |
| [Paper.java](android/app/src/main/java/fr/local/impression/Paper.java) | Formats de papier en pixels à 360 ppp |

Le protocole vient du pilote libre [epson-inkjet-printer-escpr](https://salsa.debian.org/printing-team/epson-inkjet-printer-escpr)
publié par Epson sous GPL. Le flux produit a été vérifié identique, octet pour octet,
à celui du pilote Windows officiel sur une page A4 complète.

Projet indépendant, **sans lien avec Seiko Epson Corporation**. « Epson » est une marque
de Seiko Epson Corporation.

---

## English

**Print to Epson inkjet printers from Android without the Epson app and without the cloud.**

An Android **print service plugin** (like Epson Print Enabler): install it once and your
printer shows up in the native **Print** menu of every app. Pages are rasterized on the
phone into Epson's ESC/P-R language and sent straight to the printer over Wi-Fi (raw
port 9100). Nothing leaves your local network: no account, no analytics, no server.

- **Compatible**: Wi-Fi Epson inkjets that speak ESC/P-R — the 617 models of Epson's
  official Linux driver ([list](android/app/src/main/assets/escpr_models.txt)). Not
  ESC/P-R2-only models, not lasers.
- **Tested** on an EcoTank ET-2600 only; reports for other models are welcome.
- **Install**: download [ImpressionLocale.apk](https://github.com/Ghermin/impression-locale/releases/latest/download/ImpressionLocale.apk),
  open it, then print from any app. The app UI is in French; the print dialog is Android's own.
- **Limits**: 360 dpi, plain paper, normal quality; no borderless, no duplex, no ink levels.
  An interrupted job can leave the printer stuck — power-cycle it.

Independent project, not affiliated with Seiko Epson Corporation.

## Licence / License

GPL-2.0-or-later — see [LICENSE](LICENSE).
