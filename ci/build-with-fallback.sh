#!/usr/bin/env bash
# Собирает мод. Если javac падает с ошибкой компиляции, применяет следующий запасной вариант и собирает снова.
# Запасные варианты берутся из переменной окружения FALLBACKS: по одной shell-команде на строку, от лучшего к простому.
# Сбои сети и зависимостей (например, 429 от Maven) запасные варианты не запускают: за повтор отвечает nick-fields/retry.
# Использование: build-with-fallback.sh <команда сборки...>
set -uo pipefail

log="$(mktemp)"
attempt() {
  "$@" 2>&1 | tee "$log"
  return "${PIPESTATUS[0]}"
}

if attempt "$@"; then exit 0; fi

while IFS= read -r step; do
  [ -z "${step// }" ] && continue
  if ! grep -qE '\.java:[0-9]+: error:' "$log"; then
    echo "Сбой не похож на ошибку компиляции (сеть или зависимости): запасные варианты не применяются"
    exit 1
  fi
  echo "::warning::Ошибка компиляции, пробую запасной вариант: $step"
  bash -c "$step"
  rc=$?
  [ "$rc" -eq 10 ] && continue   # шаг не применим к этой версии: пересобирать нечего
  [ "$rc" -ne 0 ] && exit 1
  if attempt "$@"; then exit 0; fi
done <<< "${FALLBACKS:-}"

exit 1
