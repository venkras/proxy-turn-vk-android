# Third-party notices / Уведомления о сторонних компонентах

qWDTT распространяется по GNU GPL v3 (см. `LICENSE`). В проект входят
сторонние компоненты, которые сохраняют собственные лицензии. Этот файл
фиксирует обязательные уведомления для поставляемых бинарников и
материалов; он не заменяет лицензии Gradle- и Go-зависимостей, которые
указаны в их собственных артефактах.

## CSQTT (опциональный движок подключения)

- **Компонент:** клиентский движок CSQTT (`libcsqtt.so`), Rust.
- **Автор / правообладатель:** amurcanov.
- **Апстрим:** <https://github.com/amurcanov/csqtt>
- **Зафиксированная версия:** 2.5.0, тег `v2.5.0`,
  commit `fd936ef087c34cc46f26111dc2361f43b8b03c07`.
- **Лицензия:** PolyForm Noncommercial License 1.0.0
  (`PolyForm-Noncommercial-1.0.0`),
  <https://polyformproject.org/licenses/noncommercial/1.0.0>.
- **Полный текст:** `app/src/main/assets/licenses/CSQTT-LICENSE`;
  упаковывается в APK всегда, чтобы уведомление не зависело от флага
  сборки движка.
- **Что поставляется:** неизменённый бинарник, собранный из указанного
  тега; исходники CSQTT в репозиторий qWDTT не входят.
- **Зачем:** дополнительный режим подключения к CSQTT-серверам
  (UDP 46000). По умолчанию выключен, включается флагом
  `-PcsqttEngine=true`.

Обязательные уведомления апстрима:

```text
Required Notice: Copyright 2026 amurcanov.
Required Notice: Commercial use of CSQTT requires a separate written license from the licensor.
Required Notice: Redistributions and derivative works must retain attribution to the original CSQTT project and these notices.
```

Коммерческое использование движка CSQTT без отдельной письменной
лицензии правообладателя не допускается. Условия распространения и
порядок запроса разрешения описаны в
`docs/csqtt-mode/LICENSE-NOTES.md`.

## Прочие компоненты

Остальные сторонние библиотеки (AndroidX/Compose, WireGuard tunnel,
OkHttp, JSch, ZXing, Go-модули и т. д.) используются на условиях их
собственных лицензий и не изменяются этим документом. Их тексты
доступны в соответствующих артефактах зависимостей и, где требуется,
включаются в APK штатными средствами Gradle.
