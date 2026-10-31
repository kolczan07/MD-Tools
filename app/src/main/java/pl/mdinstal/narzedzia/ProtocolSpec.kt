package pl.mdinstal.narzedzia

sealed class PField {
    abstract val id: String
    abstract val label: String
}

data class PText(override val id: String, override val label: String) : PField()
data class PYesNo(override val id: String, override val label: String) : PField()
data class PMulti(override val id: String, override val label: String, val options: List<String>) : PField()
data class PChoice(override val id: String, override val label: String, val options: List<String>) : PField()

data class PSection(val title: String, val fields: List<PField>)

/** Opis tabeli protokołu: jedno źródło prawdy dla formularza na ekranie i dla PDF. */
object ProtocolSpec {
    const val NOTE = "*Pomiary są dokonywane 15 minut po uruchomieniu pompy ciepła"

    val sections: List<PSection> = listOf(
        PSection(
            "Informacje o obiekcie", listOf(
                PText("model", "Model pompy ciepła"),
                PText("serial", "Numer seryjny pompy ciepła"),
                PText("address", "Adres:"),
                PText("area", "Powierzchnia grzewcza"),
                PText("energy", "Klasa energetyczna budynku"),
                PYesNo("dist", "Czy miejsce instalacji spełnia wymagania (odległości)?")
            )
        ),
        PSection(
            "Część hydrauliczna", listOf(
                PYesNo("filters", "Osadniki zanieczyszczeń (filtry) obieg zewnętrzny, obieg wewnętrzny"),
                PYesNo("vents", "Odpowietrzniki w najwyższych punktach"),
                PText("p_in", "Ciśnienie w obiegu wewnętrznym (od 1 bar do 2 bar)"),
                PText("p_out", "Ciśnienie w obiegu zewnętrznym (od 1 bar do 2 bar)"),
                PText("fluid", "Rodzaj płynu w obiegu zewnętrznym"),
                PText("glycol", "Stężenie glikolu w obiegu zewnętrznym (temperatura zamarzania)"),
                PYesNo("exp_out", "Naczynie wzbiorcze dla obiegu zewnętrznego"),
                PYesNo("exp_in", "Naczynie wzbiorcze dla obiegu wewnętrznego"),
                PYesNo("exp_hw", "Naczynie wzbiorcze dla obiegu ciepłej wody"),
                PYesNo("refill", "Zespół napełniania i cyrkulacji w obiegu zewnętrznym"),
                PYesNo("insp", "Kontrola naczyń wzbiorczych")
            )
        ),
        PSection(
            "Część elektryczna", listOf(
                PYesNo("breaker", "Oddzielny wyłącznik nadprądowy w rozdzielnicy elektrycznej budynku spełniający wymagania pompy ciepła"),
                PYesNo("cab_hp", "Kabel poprowadzony do pompy ciepła spełniający wymagania"),
                PYesNo("cab_pan", "Kabel zainstalowany do miejsca montażu panelu sterowania"),
                PYesNo("cab_sen", "Kabel zainstalowany dla czujnika zewnętrznego")
            )
        ),
        PSection(
            "Konfiguracja", listOf(
                PMulti("heating", "System grzewczy:", listOf("podłogowy", "grzejniki")),
                PYesNo("buf", "Zbiornik buforowy"),
                PYesNo("ext_hw", "Zewnętrzny zasobnik ciepłej wody"),
                PYesNo("cool", "Funkcja chłodzenia"),
                PYesNo("serv", "Podłączenie do serwera")
            )
        ),
        PSection(
            "Parametry podczas uruchamiania pompy ciepła", listOf(
                PText("t_out", "Temperatura zewnętrzna"),
                PText("t_in", "Temperatura wewnętrzna"),
                PText("t_gl_in", "Temperatura glikolu na wejściu"),
                PText("t_gl_out", "Temperatura glikolu na wyjściu"),
                PText("t_sup", "Temperatura zasilania czynnika grzewczego"),
                PText("t_ret", "Temperatura powrotu czynnika grzewczego"),
                PText("t_hw", "Temperatura ciepłej wody"),
                PText("t_evap", "Temperatura parowania czynnika chłodniczego"),
                PText("t_cond", "Temperatura skraplania czynnika chłodniczego"),
                PText("eev", "Kąt otwarcia EEV %"),
                PText("spd_out", "Prędkość zewnętrznej pompy obiegowej %"),
                PText("spd_in", "Prędkość wewnętrznej pompy obiegowej %"),
                PChoice("hw_prep", "Przygotowanie ciepłej wody:", listOf("włączone", "wyłączone"))
            )
        )
    )

    /** Pola podpisów: id, etykieta, podpis pod linią (lub null), czy linia krótka. */
    data class Sig(val id: String, val label: String, val sub: String?, val short: Boolean)

    val signatures = listOf(
        Sig("sig_person", "Osoba wypełniająca deklarację:", "(stanowisko, imię, nazwisko, podpis)", false),
        Sig("sig_client", "Klient odbierający prace:", "(imię, nazwisko, podpis)", false),
        Sig("sig_date", "Data wypełnienia deklaracji:", null, true)
    )

    val contact = listOf(
        "UAB \"IGLU TECH\"",
        "Ozo g. 12A-1, LT-08200 Vilnius",
        "info@iglutech.eu",
        "+370 5 239 4949"
    )

    const val TITLE = "PROTOKÓŁ URUCHOMIENIA I REGULACJI POMPY CIEPŁA"
}
