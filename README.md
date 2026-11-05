# Impression locale

**Imprimer sur une jet d'encre Epson depuis Android, sans appli Epson et sans passer par Internet.**

Site : https://ghermin.github.io/impression-locale/ — Télécharger : [ImpressionLocale.apk](https://github.com/Ghermin/impression-locale/releases/latest/download/ImpressionLocale.apk)

C'est un **plug-in d'impression Android**, comme Epson Print Enabler : on l'installe une
fois, puis l'imprimante apparaît dans le menu **Imprimer** natif de toutes les applis
(Partager / ⋮ > Imprimer). Pas besoin d'ouvrir l'appli.

- Le téléphone convertit lui-même les pages dans le langage des jet d'encre Epson
  (ESC/P-R) et les envoie **directement à l'imprimante** sur le Wi-Fi (port 9100).
- **Les documents ne sortent pas du réseau local** : pas de compte, pas de statistiques,
  pas de serveur, pas de pub. Seule connexion extérieure : l'appli lit la dernière
  version publiée sur GitHub (rien n'est envoyé) et se met à jour toute seule. Tout est
  lisible dans [android/](android/).
- L'imprimante est trouvée automatiquement : à chaque ouverture de l'appli, elle sonde
  le Wi-Fi (port 9100, puis modèle demandé en SNMP), et l'écran d'impression la cherche
  aussi en mDNS/Bonjour. Sinon, on peut ajouter son adresse IP dans l'appli.
- Formats : A4, Lettre, Légal, A5, A6, B5, 10×15, 13×18, carte postale ; couleur ou
  noir et blanc ; copies multiples. Android 8 ou plus récent.
- Thème clair ou sombre automatique, selon le réglage du téléphone (appli) ou de
  l'appareil (site).

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
4. Dans l'appli « Impression locale », **Imprimer une page de test** (et accepter les
   notifications, pour être prévenu des mises à jour).

**Mises à jour automatiques** (comme CamTel et Averse) : à chaque ouverture, l'appli
regarde la dernière release ; si elle est plus récente, elle la télécharge et l'installe
elle-même. Android demande une confirmation la première fois, puis plus du tout. Depuis
l'écran d'impression, une notification prévient qu'une version attend.

> Les versions 1.0 à 1.3 étaient signées avec une autre clé : pour passer à la 1.4,
> désinstaller l'ancienne puis installer la nouvelle une fois à la main.

**Publier une version** : pousser l'étiquette `vX.Y` ; la CI compile l'APK signé (secrets
`ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD`) et crée la release.

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
Sous Windows, `android/build-local.ps1` fait la même chose. Il signe avec
`~/.android/impression-locale.jks` (mot de passe dans `impression-locale.properties`) s'il
existe, la même clé que la CI : un APK compilé en local peut alors être mis à jour par
les releases.

## Comment ça marche

| Fichier | Rôle |
|---|---|
| [Discovery.java](android/app/src/main/java/fr/local/impression/Discovery.java) | Cherche `_pdl-datastream._tcp` en mDNS, garde les Epson ESC/P-R |
| [NetworkScan.java](android/app/src/main/java/fr/local/impression/NetworkScan.java) | À l'ouverture de l'appli : sonde le sous-réseau Wi-Fi sur le port 9100, demande le modèle en SNMP, garde les Epson ESC/P-R |
| [UpdateChecker.java](android/app/src/main/java/fr/local/impression/UpdateChecker.java) | Mise à jour automatique depuis la dernière release GitHub (PackageInstaller) |
| [EpsonPrintService.java](android/app/src/main/java/fr/local/impression/EpsonPrintService.java) | Service d'impression Android : rend chaque page du PDF par bandes à 360 ppp et les envoie |
| [EscprWriter.java](android/app/src/main/java/fr/local/impression/EscprWriter.java) | Écrit le flux ESC/P-R (en-tête, lignes RGB compressées RLE, fin de travail) |
| [Paper.java](android/app/src/main/java/fr/local/impression/Paper.java) | Formats de papier en pixels à 360 ppp |

Le protocole vient du pilote libre [epson-inkjet-printer-escpr](https://salsa.debian.org/printing-team/epson-inkjet-printer-escpr)
publié par Epson sous GPL. Le flux produit a été vérifié identique, octet pour octet,
à celui du pilote Windows officiel sur une page A4 complète.

Projet indépendant, **sans lien avec Seiko Epson Corporation**. « Epson » est une marque
de Seiko Epson Corporation.

## Licence

GPL-2.0-or-later : voir [LICENSE](LICENSE).
