# material-sync

Сервис на принтере. Он добавляет пользовательские пластики из **orca-sync** в библиотеку материалов
прошивки Creality K2. После этого слоту CFS или держателю можно назначить, например, «НИТ PETG» с экрана
принтера, из приложения K2 Control или из Creality Print. Принтер хранит у слота настоящий ID материала,
и слайсер по производителю и названию слота находит нужный пресет.

## Как работает

- Прошивка держит библиотеку в `/mnt/UDISK/creality/userdata/box`:
  - `material_database.json` хранит пластики: `base` (id, производитель, название, тип, температуры)
    и `kvParam` с ключами OrcaSlicer;
  - `material_option.json` хранит связку «производитель → тип → названия» для выбора на экране.
- Демоны прошивки (`web-server`, `master-server`, `display-server`) следят за этими файлами через inotify
  и перечитывают их сами, перезапуск не нужен.
- Сервис читает `/mnt/UDISK/orca-sync/data/db.json` и берёт пресеты пластиков под K2
  (`… @Creality K2 …`). Для каждого названия до ` @` создаётся один материал, настройки берутся
  из пресета под сопло, которое задано на принтере (`extruder.nozzle_diameter`, команда
  `SET_NOZZLE_DIAMETER`); если такого варианта нет, берётся пресет под 0.4. При смене сопла библиотека пересобирается.
- Наследование (`inherits`) разворачивается по вашим же пресетам. Системный родитель
  (`CR-PETG @K2 Plus-all`, `Creality Generic PLA @K2-all`) берётся из библиотеки принтера и служит шаблоном
  `kvParam`, а всё, что задано в пресете, переписывается поверх.
- ID материала имеет вид `U` + 4 hex-символа от хэша названия, то есть он стабилен между запусками. Это 5 символов,
  как у прошивки (`00003`, `E1001`). Klipper видит такой слот как `material_type: 0U1913`.
- Пластики, которые уже есть в библиотеке (копии системных пресетов: Hyper PETG, CR-TPU…), пропускаются.
- Свои записи сервис помечает `base.alias = "orca-sync"` и пересобирает при каждом изменении orca-sync.
  Если прошивка перезапишет библиотеку (обновление из Creality Cloud), сервис добавит записи снова.
  Остальную библиотеку он не трогает.
- Перед первой записью сохраняются копии `material_database.json.orig` и `material_option.json.orig`.

## Установка

```sh
scp material_sync.py root@PRINTER:/mnt/UDISK/material-sync/
scp material-sync.init root@PRINTER:/etc/init.d/material-sync
ssh root@PRINTER 'chmod +x /etc/init.d/material-sync && /etc/init.d/material-sync enable && /etc/init.d/material-sync start'
```

Проверка без записи: `python3 /mnt/UDISK/material-sync/material_sync.py --once --dry-run`.
Лог: `logread | grep python3`.

Удаление:
1. Выполнить `/etc/init.d/material-sync disable && /etc/init.d/material-sync stop`.
2. Вернуть файлы из `*.orig`, если `material_database.json` с тех пор не обновлялся из облака.

## Синхронизация CFS в OrcaSlicer

Начиная с 2.4.x Orca сопоставляет слот с пресетом по названию и производителю, записанным в слоте, а затем
выбирает пресет **без родителя** с тем же `filament_id` (`PresetBundle::sync_ams_list`). Отсюда два случая:

- Пресеты со своим `filament_id` и без `inherits` (созданные через «Создать пластик» или отвязанные)
  подставляются точно: PlastiQ PETG, Eryone ABS/ASA Carbon Fiber, U3-Print PETG GFF15.
- Пресеты, унаследованные от системного (НИТ PETG ← CR-PETG), имеют `filament_id` родителя, и Orca
  подставит родителя. Чтобы подставлялся ваш пресет, создайте его в Orca через «Создать пластик»: Orca
  выдаст ему свой `filament_id`.

Приложение K2 Control и облачный слайсер подбирают пресет по названию, поэтому у них это работает
для всех пластиков.
