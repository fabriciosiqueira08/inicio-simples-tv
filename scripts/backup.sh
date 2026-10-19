#!/usr/bin/env bash
# Salva lista de apps e configurações da TV antes de mexer em qualquer coisa.
# Uso: ./backup.sh IP:PORTA [pasta]   (pasta padrão: backup-AAAA-MM-DD)
# O backup tem dados do aparelho (IDs, rede): não suba pro git.
set -euo pipefail
ALVO="${1:?uso: ./backup.sh IP:PORTA [pasta]}"
B="${2:-backup-$(date +%F)}"
mkdir -p "$B"

adb connect "$ALVO" >/dev/null
adb -s "$ALVO" get-state >/dev/null 2>&1 || { echo "TV não conectada em $ALVO (ligada? aceitou o aviso de depuração?)"; exit 1; }
D="adb -s $ALVO shell"

$D pm list packages -f -u -U --show-versioncode > "$B/pacotes-todos.txt"
$D pm list packages -e > "$B/pacotes-ativos.txt"
$D pm list packages -d > "$B/pacotes-desativados.txt"
$D pm list packages -s > "$B/pacotes-sistema.txt"
$D pm list packages -3 > "$B/pacotes-terceiros.txt"
for ns in system secure global; do $D settings list $ns > "$B/settings-$ns.txt"; done
$D getprop > "$B/getprop.txt"
$D cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$B/home-atual.txt"
echo "backup em $B ($(wc -l < "$B/pacotes-ativos.txt") apps ativos)"
