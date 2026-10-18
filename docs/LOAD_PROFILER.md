# Профилировщик загрузки (Load Profiler)

NEIExtend записывает подробные логи загрузки Minecraft 1.7.10 с модами — от старта JVM до первого
игрового тика. Цель — найти, что именно тормозит загрузку, и ускорить её.

## Что измеряется

| Что | Как |
|---|---|
| Таймлайн загрузки (секунды от старта JVM) | coremod, `Minecraft.startGame`, `beginMinecraftLoading`, `finishMinecraftLoading`, каждое состояние FML, перезагрузки ресурсов, главное меню, первый тик |
| Время каждого состояния FML | `LOADING`, `CONSTRUCTING`, `PREINITIALIZATION`, `INITIALIZATION`, `POSTINITIALIZATION`, `AVAILABLE` |
| Время каждого мода в каждой фазе | Construction, PreInit, Init, IMC, PostInit, LoadComplete и т.д. (через `LoadController.sendEventToModContainer`) |
| Сканирование папки mods | `Loader.identifyMods` |
| Reload-листенеры ресурсов | склейка текстур (`TextureManager`), звуки, шрифты, языки, модели модов |
| Трансформеры классов (ASM/coremod) | собственное время каждого трансформера, число классов, самые медленные классы |
| Сэмплинг стека потока загрузки | топ методов/пакетов, где реально тратится время (каждые 10 мс) |
| Статистика JVM | GC, JIT, число классов, heap/metaspace |

## Где смотреть

- `logs/fml-client-latest.log` / консоль — строки логгера `NEIExtend-LoadProfiler`: таймлайн
  в реальном времени, медленные события (> 250 мс), итоговый топ модов и трансформеров.
- `logs/neiextend-loadprofile.txt` — полный отчёт.
- `logs/neiextend-loadprofile-mods.csv` — время модов по фазам (удобно сравнивать запуски).
- `logs/neiextend-loadprofile-transformers.csv` — время трансформеров.

## Настройки (аргументы JVM)

| Аргумент | По умолчанию | Описание |
|---|---|---|
| `-Dneiextend.profiler=false` | `true` | полностью выключить профилировщик |
| `-Dneiextend.profiler.transformers=false` | `true` | не измерять трансформеры классов |
| `-Dneiextend.profiler.sampleInterval=10` | `10` | интервал сэмплера стека в мс, `0` — выключить |

Для подробных строк по каждому моду включите уровень `DEBUG` для логгера `NEIExtend-LoadProfiler`.

## Ограничения

- Время до загрузки coremod-а NEIExtend (JVM, лаунчер, LaunchWrapper, поиск coremod-ов) видно только
  одной строкой в таймлайне.
- Трансформер Mixin, `DeobfuscationTransformer`, `EventSubscriptionTransformer` и `TerminalTransformer`
  не оборачиваются (Mixin и FML ищут их по типу/имени класса). Для Mixin можно включить его
  собственный профайлер: `-Dmixin.debug.profiler=true`.
- Классы, загруженные до `Minecraft.startGame`, трансформеры измеряют только частично: часть
  трансформеров регистрируется позже.
- Отчёт пишется только на клиенте (мод клиентский); конец загрузки — первый игровой тик.

## Как использовать для ускорения

1. Запустить игру, дождаться главного меню, открыть `logs/neiextend-loadprofile.txt`.
2. Посмотреть таймлайн: какая стадия занимает больше всего (`+delta`).
3. Таблица модов — кто тормозит в PreInit/Init/PostInit; секция сэмплера — какие методы внутри.
4. Таблица трансформеров — какие coremod-ы дорого обходятся на каждом классе.
5. Reload-листенеры — сколько стоит склейка текстур и сколько раз ресурсы перезагружались.
6. После каждой оптимизации сравнить CSV-файлы двух запусков.
