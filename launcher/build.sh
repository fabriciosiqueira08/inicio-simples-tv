#!/usr/bin/env bash
# Gera tela-inicial.apk sem Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Precisa de: Android SDK (build-tools + uma plataforma >= 30) e um JDK 17.
#   ANDROID_HOME  SDK (padrão ~/Android/Sdk)
#   JAVA_HOME     JDK 17 (padrão: primeiro JDK 17 achado em ~/.gradle/jdks)
#   KEYSTORE      chave de assinatura (padrão ./chave.jks, criada se não existir)
# Guarde a chave: sem ela, uma versão nova não instala por cima da atual.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
JAVA_HOME=${JAVA_HOME:-$(ls -d "$HOME"/.gradle/jdks/*17* 2>/dev/null | head -1)}
JDK=${JAVA_HOME:?defina JAVA_HOME apontando pra um JDK 17}/bin
KS=${KEYSTORE:-chave.jks}

rm -rf build && mkdir -p build/classes build/dex
[ -f "$KS" ] || "$JDK/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias home -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Inicio Simples" >/dev/null

ASSETS=(); [ -d assets ] && ASSETS=(-A assets)
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml "${ASSETS[@]}"
"$JDK/javac" -nowarn -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && "$JDK/jar" uf ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out tela-inicial.apk build/aligned.apk
echo "ok: $(pwd)/tela-inicial.apk"
