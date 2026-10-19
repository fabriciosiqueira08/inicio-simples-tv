#!/usr/bin/env bash
# Volta a TV ao estado do backup: reativa todo app que estava ativo no backup
# e hoje está desativado, e devolve a tela inicial original do Google TV.
# Uso: ./restaurar.sh PASTA_DO_BACKUP IP:PORTA
set -u
B="${1:?uso: ./restaurar.sh PASTA_DO_BACKUP IP:PORTA}"
ALVO="${2:?uso: ./restaurar.sh PASTA_DO_BACKUP IP:PORTA}"
HOME_ORIGINAL="com.google.android.apps.tv.launcherx/.home.HomeActivity"
[ -f "$B/pacotes-ativos.txt" ] || { echo "$B/pacotes-ativos.txt não existe (rode o backup.sh antes)"; exit 1; }

adb connect "$ALVO" >/dev/null
adb -s "$ALVO" get-state >/dev/null 2>&1 || { echo "TV não conectada em $ALVO (ligada? aceitou o aviso de depuração?)"; exit 1; }
D="adb -s $ALVO shell"

n=0
for pkg in $($D pm list packages -d | tr -d '\r' | sed 's/^package://'); do
  if grep -qx "package:$pkg" "$B/pacotes-ativos.txt"; then
    echo "reativando $pkg"
    $D pm enable --user 0 "$pkg"
    n=$((n+1))
  fi
done
echo "$n app(s) reativado(s)."

echo "voltando a tela inicial original"
$D cmd package set-home-activity --user 0 "$HOME_ORIGINAL"
$D input keyevent KEYCODE_HOME
echo "pronto. Tela inicial atual:"
$D cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
