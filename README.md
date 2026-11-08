# Compteur Moto

Compteur GPS pour moto-taxi (DT) avec suivi des services, de l'argent et du véhicule. Application Android (WebView), entièrement hors ligne : les données restent sur le téléphone.

**Télécharger :** [release/Compteur-Moto.apk](release/Compteur-Moto.apk) (Android 7+)

## Fonctions

- **Compteur** : prix MIN–MAX en direct (prise en charge, prix au km en fourchette, attente par minute, course minimum), reçu détaillé, analyse de la course, estimation de prix, navigation Google Maps, fenêtre flottante pendant la navigation. Le compteur continue si l'application est fermée.
- **Accueil** : chiffres du jour, raccourcis, objectifs (jour / semaine / mois, km, épargne) et budgets carburant / entretien, projection de fin de mois.
- **Service** : début, pauses, fin de service, chiffres en direct, notes et incidents, bilan.
- **Argent** : bénéfice réel, réserves (essence, entretien, frais fixes, épargne), dépenses avec photos du reçu, historique. Date de début du suivi.
- **Analyse** : revenus, distance, activité, finances, conseils.
- **Véhicule** : kilométrage, consommation (L/100 km), coût par km, dernière vidange, synchronisation des pleins et entretiens depuis la sauvegarde de l'application SymJet.
- **Données** : sauvegarde automatique dans un dossier choisi, export / import, export Excel.

## Construire

Sans Gradle : `bash build.sh` (aapt2, javac, d8, zipalign, apksigner du SDK Android 34 et un JDK 17, dossier défini par `ANDROID_TOOLS` ou un fichier local `.tools`).
Le mot de passe du keystore est lu dans un fichier local `.kspass`, jamais publié.

## Structure

- `assets/index.html` : toute l'interface et la logique (JavaScript)
- `src/com/personal/compteur/` : `MainActivity`, `Bridge` (pont JavaScript ↔ Android), `MeterService` (GPS en arrière-plan), `Backup`
- `AndroidManifest.xml`, `res/`
