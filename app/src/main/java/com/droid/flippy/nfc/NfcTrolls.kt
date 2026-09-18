package com.droid.flippy.nfc

data class NfcTroll(
    val name: String,
    val descriptionEn: String,
    val descriptionRu: String,
    val url: String
) {
    fun localizedDescription(isRussian: Boolean): String =
        if (isRussian) descriptionRu else descriptionEn
}

val NFC_TROLLS = listOf(
    NfcTroll("RickRoll", "Never gonna give you up", "Никогда тебя не брошу", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
    NfcTroll("Fake Update (Win10)", "Windows 10 update screen", "Экран обновления Windows 10", "https://fakeupdate.net/win10ue/"),
    NfcTroll("PC Fake Update", "Universal PC update prank", "Универсальный розыгрыш с обновлением ПК", "https://dushusir.com/fake/"),
    NfcTroll("z0r.de", "Random flash animations", "Случайные флеш-анимации", "https://z0r.de/1"),
    NfcTroll("Prank Owl", "Prank calls and sounds", "Пранк-звонки и звуки", "https://www.prankowl.com/"),
    NfcTroll("MonkeyType", "Typing speed test (prank)", "Тест скорости печати (пранк)", "https://monkeytype.com"),
    NfcTroll("Akinator (EN)", "Web genie", "Веб-джинн", "https://en.akinator.com"),
    NfcTroll("Pointer Troll", "Find the invisible cow/pointer", "Найди невидимую корову/указатель", "https://pointerpointer.com/"),
    NfcTroll("Google Gravity", "Everything falls down", "Всё падает вниз", "https://mrdoob.com/projects/chromeexperiments/google-gravity/"),
    NfcTroll("Internet Map", "Interactive internet map", "Интерактивная карта интернета", "https://internet-map.net/"),
    NfcTroll("Crash Browser", "Link that hammers the browser", "Ссылка, нагружающая браузер", "https://crashsafari.com"),
    NfcTroll("The Annoying Site", "Max volume and popups", "Максимальная громкость и всплывающие окна", "https://theannoyingsite.com/"),
    NfcTroll("Shit In A Box", "Exactly what it says", "Именно то, что написано", "https://www.shitexpress.com/"),
    NfcTroll("Unfair Mario", "Infuriating platformer", "Раздражающий платформер", "https://www.unfair-mario.com/"),
    NfcTroll("Wayback Machine", "Time travel to the past", "Путешествие в прошлое", "https://archive.org/web/"),
    NfcTroll("10 Fast Fingers", "Typing speed test", "Тест скорости печати", "https://10fastfingers.com/"),
    NfcTroll("HackTyper", "Pretend to be a hacker", "Притворись хакером", "https://hacktyper.net/"),
    NfcTroll("Geoguesser", "Where in the world are you?", "Где ты находишься в мире?", "https://www.geoguessr.com/"),
    NfcTroll("ASCII Archive", "Huge ASCII art collection", "Огромная коллекция ASCII-арта", "https://www.asciiart.eu/"),
    NfcTroll("Floating QR", "Moving QR code", "Движущийся QR-код", "https://floating-qr.com/"),
    NfcTroll("MD5 Generator", "Hash anything", "Хешируй что угодно", "https://www.md5hashgenerator.com/"),
    NfcTroll("Random Person", "This person does not exist", "Этого человека не существует", "https://thispersondoesnotexist.com/"),
    NfcTroll("101 Useful Sites", "List of useful web tools", "Список полезных веб-инструментов", "https://101usefulsites.com/"),
    NfcTroll("Mobile Update", "Fake Android/iOS update", "Фейковое обновление Android/iOS", "https://fakeupdate.net/mobile/"),
    NfcTroll("Water Google", "Underwater search", "Подводный поисковик", "https://elgoog.im/underwater/"),
    NfcTroll("Google Eastereggs", "Hidden Google tricks", "Скрытые трюки Google", "https://elgoog.im/"),
    NfcTroll("Random Useless Site", "Takes you to a random site", "Переносит на случайный сайт", "https://theuselessweb.com/"),
    NfcTroll("Minesweeper", "Classic game in one cell", "Классическая игра в одну клетку", "https://onesquareminesweeper.com/"),
    NfcTroll("The Red Button", "Don\'t press it.", "Не нажимай её.", "https://www.i-am-bored.com/the-red-button/"),
    NfcTroll("Emkei\'s Mailer", "Fake sender email prank", "Пранк с поддельным отправителем почты", "https://emke.cz/"),
    NfcTroll("GTAV Geoguesser", "Guess where you are in Los Santos", "Угадай, где ты в Лос-Сантосе", "https://gta-geoguessr.com/"),
    NfcTroll("Idiot Tribute", "Classic \'You are an idiot\' animation", "Классическая анимация \'You are an idiot\'", "https://the-idiot-test.com/")
)
