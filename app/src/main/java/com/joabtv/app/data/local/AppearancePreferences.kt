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

/**
 * Tamanho da interface da TV. A TV mede tudo em rem (1/120 da largura da tela), então em TV pequena ou vista de longe
 * o texto fica pequeno. O fator multiplica o rem e o texto, os cartões e as margens crescem juntos. O passo maior para em 1,2
 * porque acima disso o destaque, a barra do menu Superior e a primeira fileira não cabem mais juntos na altura da tela.
 */
enum class InterfaceSize(val key: String, val label: String, val scale: Float) {
    STANDARD("standard", "Padrão", 1f), MEDIUM("medium", "Médio", 1.1f), LARGE("large", "Grande", 1.2f);

    companion object {
        fun fromKey(key: String?): InterfaceSize = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

data class AppearancePreferences(
    val theme: AppTheme = AppTheme.GRAPHITE,
    val motion: InterfaceMotion = InterfaceMotion.SMOOTH,
    val menu: MenuStyle = MenuStyle.SIDE,
    val uiSize: InterfaceSize = InterfaceSize.STANDARD,
)
