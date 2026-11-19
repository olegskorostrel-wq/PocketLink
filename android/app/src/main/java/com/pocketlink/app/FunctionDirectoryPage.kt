package com.pocketlink.app

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ScrollView

/** Searchable navigation catalogue. Entries open the function; they never execute power commands. */
class FunctionDirectoryPage(act: MainActivity) : Page(act) {
    private data class Entry(val name: String, val aliases: String, val open: () -> Unit)
    private fun tools(mode: Int) { act.openUtility(ToolsPage(act).apply { openSection(mode) }) }
    private val entries = listOf(
        Entry("Тачпад и мышь", "курсор выделение перетаскивание пульт", { act.openUtility(RemotePage(act)) }),
        Entry("Клавиатура", "ввод текст клавиши", { act.openUtility(RemotePage(act).apply { openSection(0) }) }),
        Entry("Мультимедиа", "громкость звук пауза воспроизведение", { act.openUtility(RemotePage(act).apply { openSection(1) }) }),
        Entry("Презентации", "слайды показ вперед назад powerpoint", { act.openUtility(RemotePage(act).apply { openSection(2) }) }),
        Entry("Экран компьютера на телефоне", "трансляция монитор полный экран", { act.openUtility(ScreenPage(act)) }),
        Entry("Экран телефона на компьютере", "scrcpy скрапи usb wifi демонстрация управление", { act.openUtility(MirrorHelpPage(act)) }),
        Entry("Файлы и передача", "папки отправка загрузка скачать", { act.openUtility(FilesPage(act)) }),
        Entry("Буфер обмена", "копировать вставить текст clipboard", { act.openUtility(HomePage(act)) }),
        Entry("YouTube — поиск и пульт", "ютуб видео ролики обложки браузер", { act.openUtility(YouTubePage(act)) }),
        Entry("Звук и устройства воспроизведения", "микшер наушники монитор динамики колонки громкость", { act.openUtility(MixerPage(act)) }),
        Entry("Окна и состояние ПК", "температура память процессор яркость", { act.openUtility(DesktopPage(act)) }),
        Entry("Команды и сочетания клавиш", "макрос кнопка горячие клавиши", { tools(0) }),
        Entry("Программы на ПК", "приложения запуск поиск", { tools(1) }),
        Entry("Процессы", "диспетчер задач завершить", { tools(2) }),
        Entry("Питание компьютера", "выключить включить сон блокировка перезагрузка wake on lan", { tools(3) }),
        Entry("Телефон как веб-камера", "камера discord дискорд вебка", { act.openUtility(WebcamPage(act)) }),
        Entry("Телефон как микрофон", "голос звук запись", { act.openUtility(MicrophonePage(act)) }),
        Entry("Bluetooth-пульт и геймпад", "блютуз джойстик кнопки курки", { act.openUtility(BluetoothPage(act)) }),
        Entry("Подключение и разрешения", "сопряжение qr сеть vpn исправить ошибка", { act.openUtility(DevicePage(act)) }),
        Entry("Темы и оформление", "цвет светлая темная фиолетовая кастомизация", { act.openUtility(DevicePage(act)) }),
        Entry("Обновления приложения", "версия github скачать apk", { act.openUtility(UpdatesPage(act)) })
    )
    private val list = vbox(act, 0)
    private val search = input(act, "Найти функцию: звук, файлы, экран…")
    override val root: View = vbox(act,12).apply {
        addView(pageHeading(act,"Все функции")); addView(search)
        addView(ScrollView(act).apply { addView(list) },lp(h=0,weight=1f))
    }
    init {
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){ render(s.toString()) }
            override fun afterTextChanged(s:Editable?){}
        }); render("")
    }
    private fun render(query:String) {
        list.removeAllViews()
        val terms=query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val matches=entries.filter { item -> terms.all { (item.name+" "+item.aliases).lowercase().contains(it) } }
        matches.forEach { item -> list.addView(actionRow(act,item.name,"Открыть") { item.open() },lp(t=8,ctx=act)) }
        if(matches.isEmpty())list.addView(muted(act,"Ничего не найдено. Попробуйте другое слово."))
    }
}

class MirrorHelpPage(act:MainActivity):Page(act) {
    override val root:View=ScrollView(act).apply { addView(vbox(act,12).apply {
        addView(pageHeading(act,"Экран телефона на ПК"))
        addView(btn(act,"Запустить экран и управление",1){ if(Hub.state==Hub.State.ONLINE) Hub.send("mirror.quick") else Hub.toast("Сначала подключитесь к ПК") })
        addView(btn(act,"Проверить и исправить на ПК") { if(Hub.state==Hub.State.ONLINE) Hub.send("mirror.repair") else Hub.toast("Сначала подключитесь к ПК") },lp(t=8,ctx=act))
        addView(btn(act,"Открыть настройки разработчика телефона") {
            runCatching { act.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                .onFailure { Hub.toast("Настройки → О телефоне → нажмите номер сборки 7 раз, затем откройте параметры разработчика") }
        },lp(t=8,ctx=act))
        addView(muted(act,"USB: в параметрах разработчика включите отладку по USB, подключите кабель с передачей данных, разблокируйте телефон и подтвердите доступ ПК. По Wi-Fi: на Android 11+ включите беспроводную отладку, затем выполните сопряжение в разделе «Экран телефона» на ПК. Обычного подключения к Wi-Fi недостаточно."),lp(t=12,ctx=act))
    }) }
    override fun onHub(ev:String,data:Any?) { if(ev=="json" && data is org.json.JSONObject && data.optString("t")=="mirror.state") android.app.AlertDialog.Builder(act).setTitle("Подключение экрана").setMessage(data.optString("message")).setPositiveButton("Понятно",null).show() }
}
