package com.whispertflite.utils

object InputLang {
    private val langList = arrayListOf(
        Pair("en", 50259), Pair("zh", 50260), Pair("de", 50261), Pair("es", 50262),
        Pair("ru", 50263), Pair("ko", 50264), Pair("fr", 50265), Pair("ja", 50266),
        Pair("pt", 50267), Pair("tr", 50268), Pair("pl", 50269), Pair("ca", 50270),
        Pair("nl", 50271), Pair("ar", 50272), Pair("sv", 50273), Pair("it", 50274),
        Pair("id", 50275), Pair("hi", 50276), Pair("fi", 50277), Pair("vi", 50278),
        Pair("he", 50279), Pair("uk", 50280), Pair("el", 50281), Pair("ms", 50282),
        Pair("cs", 50283), Pair("ro", 50284), Pair("da", 50285), Pair("hu", 50286),
        Pair("ta", 50287), Pair("no", 50288), Pair("th", 50289), Pair("ur", 50290),
        Pair("hr", 50291), Pair("bg", 50292), Pair("lt", 50293), Pair("la", 50294),
        Pair("mi", 50295), Pair("ml", 50296), Pair("cy", 50297), Pair("sk", 50298),
        Pair("te", 50299), Pair("fa", 50300), Pair("lv", 50301), Pair("bn", 50302),
        Pair("sr", 50303), Pair("az", 50304), Pair("sl", 50305), Pair("kn", 50306),
        Pair("et", 50307), Pair("mk", 50308), Pair("br", 50309), Pair("eu", 50310),
        Pair("is", 50311), Pair("hy", 50312), Pair("ne", 50313), Pair("mn", 50314),
        Pair("bs", 50315), Pair("kk", 50316), Pair("sq", 50317), Pair("sw", 50318),
        Pair("gl", 50319), Pair("mr", 50320), Pair("pa", 50321), Pair("si", 50322),
        Pair("km", 50323), Pair("sn", 50324), Pair("yo", 50325), Pair("so", 50326),
        Pair("af", 50327), Pair("oc", 50328), Pair("ka", 50329), Pair("be", 50330),
        Pair("tg", 50331), Pair("sd", 50332), Pair("gu", 50333), Pair("am", 50334),
        Pair("yi", 50335), Pair("lo", 50336), Pair("uz", 50337), Pair("fo", 50338),
        Pair("ht", 50339), Pair("ps", 50340), Pair("tk", 50341), Pair("nn", 50342),
        Pair("mt", 50343), Pair("sa", 50344), Pair("lb", 50345), Pair("my", 50346),
        Pair("bo", 50347), Pair("tl", 50348), Pair("mg", 50349), Pair("as", 50350),
        Pair("tt", 50351), Pair("haw", 50352), Pair("ln", 50353), Pair("ha", 50354),
        Pair("ba", 50355), Pair("jw", 50356), Pair("su", 50357)
    )

    fun getLanguageCodeById(id: Int): String {
        return langList.find { it.second == id }?.first ?: ""
    }

    fun getIdForLanguage(language: String): Int {
        if (language == "auto") return -1
        return langList.find { it.first == language }?.second ?: -1
    }
}