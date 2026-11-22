package app.listwerk.core

/**
 * Sprache für die wenigen Texte, die der Kern selbst erzeugt (Fehlermeldungen, Erinnerungstitel).
 * Die App setzt [german] passend zur gewählten Oberflächensprache; Standard ist Englisch.
 */
object CoreText {
    @Volatile var german: Boolean = false
    fun t(de: String, en: String): String = if (german) de else en
}
