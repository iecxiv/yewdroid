# Fuentes de distribución / Distribution sources

## Español

YewDroid utiliza como referencia las fuentes actuales de yewtube del repositorio `mps-youtube/yewtube`, identificadas mediante un commit concreto.

### Fuentes de yewtube

- Repositorio: https://github.com/mps-youtube/yewtube
- Rama de referencia: master
- Commit comprobado el 25 de noviembre de 2026: `35989588eb1270d55bbced991ea2edf3a11064da`
- Mensaje de versión del commit: 2.13.1
- Archivo de fuentes: https://github.com/mps-youtube/yewtube/archive/35989588eb1270d55bbced991ea2edf3a11064da.tar.gz

### Fuentes de YewDroid

El código Android, los adaptadores Python, las modificaciones y los scripts de la base de referencia están disponibles en:
https://github.com/iecxiv/yewtube-android/archive/01cd1dd6c95720fb5014626de47ec15f19aa45b6.tar.gz

El material de distribución debe reunir las fuentes originales de yewtube, sus licencias y avisos, el código de YewDroid y sus modificaciones, y los scripts e instrucciones necesarios para compilar. Las referencias por commit evitan que los enlaces a las fuentes cambien silenciosamente.

### Alcance y estado

Las copias de fuentes y los textos completos incorporados se detallan al final de este documento. La revisión de los demás componentes GPL/LGPL, las dependencias transitivas y bibliotecas nativas, y la validación de la reconstrucción siguen pendientes. No se ha inspeccionado una APK ni se certifica la correspondencia entre estos commits y un binario.

Las instrucciones disponibles están en README.md, app/build.gradle.kts y tools/build-quickjs-android.sh del commit de referencia. Antes de afirmar una reconstrucción validada, deben registrarse las versiones exactas de herramientas y dependencias y completarse el wrapper Gradle o documentarse el Gradle externo utilizado.

### Política de actualización

Se actualiza únicamente yt-dlp mientras siga siendo compatible con las demás dependencias. yewtube, yt-dlp-ejs, QuickJS y el resto de componentes se mantienen sin cambios; cualquier actualización adicional se revisa por separado. Este cambio es documental: no modifica dependencias ni genera o publica una APK.

## English

YewDroid uses the current upstream sources from `mps-youtube/yewtube` as its reference, identified by an immutable commit. The master commit checked on 25 November 2026 is `35989588eb1270d55bbced991ea2edf3a11064da`, with version commit message 2.13.1. Its source archive is linked above.

The YewDroid archive linked above contains the Android code, Python adapters, modifications and build scripts from reference commit `01cd1dd6c95720fb5014626de47ec15f19aa45b6`. Distribution materials must bring together the original yewtube sources and notices, YewDroid sources and modifications, and the required build scripts and instructions.

Included source snapshots and complete legal texts are listed below. Review of other GPL/LGPL components, transitive dependencies and native libraries, and reconstruction validation remain pending. No APK has been inspected and binary correspondence is not certified.

Consult the referenced README and build scripts. Record exact tool and dependency versions and complete the Gradle wrapper or document the external Gradle used before claiming a validated reconstruction.

Only yt-dlp is updated while compatible with the existing dependencies. yewtube, yt-dlp-ejs, QuickJS and other components remain unchanged; additional updates are reviewed separately. This documentation change does not modify dependencies or build or publish an APK.


## Materiales incorporados / Included materials

Textos completos: LICENSE y legal/licenses/. Avisos: THIRD_PARTY_NOTICES.md. Copias de fuentes: distribution/yewtube-sources-3598958.tar.gz y distribution/yewdroid-sources-01cd1dd.tar.gz. La copia de YewDroid incluye adaptadores y scripts del commit de referencia; excluye los registros de errors/. Los manifiestos registran SHA-256. Estos archivos no actualizan dependencias ni demuestran correspondencia con una APK. La revision completa de transitivas, fuentes y modificaciones requeridas, runtime y reconstruccion sigue pendiente.

Complete texts: LICENSE and legal/licenses/. Notices: THIRD_PARTY_NOTICES.md. Source snapshots and SHA-256 manifests: distribution/. The app snapshot includes adapters and scripts, excluding error logs. Dependencies are unchanged. Binary correspondence, full required transitive sources and modifications, runtime review and reconstruction validation remain pending.
