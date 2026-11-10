package com.joabtv.app.data.local

enum class AppTheme(val key: String, val label: String) {
    GRAPHITE("graphite", "Grafite"), OCEAN("ocean", "Azul"), JADE("jade", "Verde");

    companion object {
        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: GRAPHITE
    }
}

enum class InterfaceMotion(val key: String, val label: String) {
    SMOOTH("full", "Suave"), REDUCED("reduced", "Reduzido");

    companion object {
        fun fromKey(key: String?): InterfaceMotion = entries.firstOrNull { it.key == key } ?: SMOOTH
    }
}

/** Menu da TV. Lateral é o de sempre, Superior fica no topo e Oculto só aparece com a seta para a esquerda. */
enum class MenuStyle(val key: String, val label: String) {
    SIDE("side", "Lateral"), TOP("top", "Superior"), HIDDEN("hidden", "Oculto");

    companion object {
        fun fromKey(key: String?): MenuStyle = entries.firstOrNull { it.key == key } ?: SIDE
    }
}

data class AppearancePreferences(
    val theme: AppTheme = AppTheme.GRAPHITE,
    val motion: InterfaceMotion = InterfaceMotion.SMOOTH,
    val menu: MenuStyle = MenuStyle.SIDE,
)
