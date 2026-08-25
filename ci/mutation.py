#!/usr/bin/env python3
"""Читает отчёт pitest и печатает то, ради чего мутации гоняются: список выживших.

Процент выживаемости здесь не считается специально. Он мерил бы **набор мутаций**, а набор
на Kotlin наполовину состоит из кода, который написал компилятор, — и тогда цифра растёт от
того, что кто-то убрал `data class`, а не от того, что тесты стали строже.

Отсюда три корзины вместо одной:

* **шум** — мутация в коде, которого нет в исходнике. Каждое правило ниже названо поимённо
  и печатает свой счёт: правило, съевшее настоящую мутацию, обязано быть видно;
* **не покрыто** — до этой строки не доходил ни один тест. Это карта, а не приговор;
* **выжило** — тест туда доходил и мутацию не заметил. Единственная корзина, которую читают.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

# Правило = (имя, предикат). Имя печатается со счётом, поэтому «прочее» здесь быть не может:
# корзина без имени — это место, где потерянную мутацию никто не найдёт.
NOISE_RULES: list[tuple[str, object]] = [
    (
        "компиляторная проверка на null (Intrinsics)",
        lambda m: "kotlin/jvm/internal/Intrinsics" in m["desc"],
    ),
    (
        "возобновление корутины (ResultKt::throwOnFailure, Continuation::resumeWith)",
        lambda m: "kotlin/ResultKt::throwOnFailure" in m["desc"]
        or "kotlin/coroutines/Continuation::resumeWith" in m["desc"],
    ),
    (
        "маркер COROUTINE_SUSPENDED у suspend-функции",
        lambda m: m["desc"].startswith("replaced return value with null")
        and "Lkotlin/coroutines/Continuation;" in m["signature"],
    ),
    (
        # Лямбда билдера возвращает Unit, и её значение выбрасывается вызывающим. Подмена на null
        # ненаблюдаема по устройству, а не потому, что теста нет.
        "значение Unit-лямбды, которое никто не читает",
        lambda m: m["desc"].startswith("replaced return value with null")
        and m["signature"].endswith("Lkotlin/Unit;"),
    ),
    (
        "сгенерированный член data class (equals/hashCode/toString/copy/componentN)",
        lambda m: m["method"] in ("equals", "hashCode", "toString", "copy", "copy$default")
        or re.fullmatch(r"component\d+", m["method"]) is not None,
    ),
    (
        "синтетический метод компилятора (доступ, мост, лямбда)",
        lambda m: m["method"].startswith("access$")
        or m["method"].endswith("$lambda")
        or "$default" in m["method"],
    ),
]

# pitest считает эти статусы убитыми, и он прав: мутация, повесившая процесс или съевшая кучу,
# отличима от исходного кода — в этом весь вопрос. Названы отдельно, потому что читаются иначе:
# TIMED_OUT в цикле нередко значит «мутация сломала выход», а не «тест поймал».
DETECTED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}


def parse(path: Path) -> list[dict]:
    root = ET.parse(path).getroot()
    out = []
    for m in root:
        out.append(
            {
                "status": m.get("status") or "",
                "cls": m.findtext("mutatedClass") or "",
                "method": m.findtext("mutatedMethod") or "",
                "signature": m.findtext("methodDescription") or "",
                "line": int(m.findtext("lineNumber") or 0),
                "desc": m.findtext("description") or "",
                "file": m.findtext("sourceFile") or "",
            }
        )
    return out


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: mutation.py <mutations.xml>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.exists():
        print(f"нет отчёта: {path}", file=sys.stderr)
        return 2

    mutations = parse(path)
    if not mutations:
        # Пустой отчёт — это не «нечего ломать», это не запустившийся прогон.
        print(f"{path}: ноль мутаций — прогон не состоялся", file=sys.stderr)
        return 1

    noise = Counter()
    survived: dict[str, list[dict]] = defaultdict(list)
    uncovered: dict[str, list[dict]] = defaultdict(list)
    detected = 0

    for m in mutations:
        rule = next((name for name, hit in NOISE_RULES if hit(m)), None)
        if rule is not None:
            noise[rule] += 1
            continue
        if m["status"] in DETECTED:
            detected += 1
        elif m["status"] == "NO_COVERAGE":
            uncovered[m["cls"]].append(m)
        else:
            survived[m["cls"]].append(m)

    n_surv = sum(len(v) for v in survived.values())
    n_unc = sum(len(v) for v in uncovered.values())

    print(f"# {path}")
    print()
    print(f"мутаций всего      {len(mutations)}")
    print(f"  шум              {sum(noise.values())}  (правила ниже)")
    print(f"  обнаружено       {detected}")
    print(f"  не покрыто       {n_unc}  <- ни один тест не доходил")
    print(f"  ВЫЖИЛО           {n_surv}  <- читать поимённо")
    print()

    print("шум по правилам:")
    for name, count in noise.most_common():
        print(f"  {count:5}  {name}")
    for name, _ in NOISE_RULES:
        if name not in noise:
            print(f"  {0:5}  {name}  <- правило ничего не подобрало")
    print()

    for title, bucket in (("ВЫЖИЛИ", survived), ("НЕ ПОКРЫТЫ", uncovered)):
        if not bucket:
            continue
        print(f"## {title}")
        for cls in sorted(bucket, key=lambda c: -len(bucket[c])):
            items = bucket[cls]
            print(f"\n{cls}  ({len(items)})")
            for m in sorted(items, key=lambda x: x["line"]):
                print(f"  {m['file']}:{m['line']:<5} {m['method']:<28} {m['desc']}")
        print()

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BrokenPipeError:
        # `| head` закрывает трубу, и трассировка на этом месте выглядит как поломка отчёта.
        sys.stdout = None
        raise SystemExit(0)
