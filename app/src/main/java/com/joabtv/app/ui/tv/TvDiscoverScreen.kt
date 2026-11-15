package com.joabtv.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joabtv.app.domain.model.MovieItem
import com.joabtv.app.ui.movies.DiscoverViewModel
import com.joabtv.app.ui.movies.addonFilterTitle
import com.joabtv.app.ui.movies.catalogKey
import com.joabtv.app.ui.theme.*
import java.util.Calendar

/**
 * Campo da barra de filtros. O [id] é fixo (kind, catalog, sort, genres, year, cert, rating) ou "x:"
 * mais o nome de um filtro que o manifest do catálogo de addon declara (x:genre, x:year...).
 */
private data class DiscoverField(val id: String, val title: String)

/** [sub] é a linha pequena embaixo do nome, que no catálogo diz de qual addon ele vem. */
private data class FilterChoice(val id: String, val label: String, val sub: String? = null)

private const val TMDB_LABEL = "TMDB · Filtros avançados"
private const val TMDB_SUB = "Gênero, ordem, nota, ano e classificação"

@Composable
fun TvDiscoverScreen(
    onItemClick: (MovieItem) -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreFocus by remember { mutableStateOf<String?>(null) }
    var pendingField by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    val grid = rememberLazyGridState()
    val movie = state.kind == DiscoverViewModel.Kind.MOVIE
    val catalog = state.catalog
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val sorts = listOf(
        FilterChoice(DiscoverViewModel.SORT_POPULAR, "Populares"),
        FilterChoice(DiscoverViewModel.SORT_TOP_RATED, "Melhor nota"),
        FilterChoice(if (movie) DiscoverViewModel.SORT_RELEASE_MOVIE else DiscoverViewModel.SORT_RELEASE_TV, "Lançamentos"),
        FilterChoice(DiscoverViewModel.SORT_TITLE, "Título A-Z"),
    )
    // Com catálogo de addon valem os filtros que ele declara. Sem catálogo ficam os do TMDB.
    val fields = buildList {
        add(DiscoverField("kind", "Conteúdo"))
        if (state.kindCatalogs.isNotEmpty()) add(DiscoverField("catalog", "Catálogo"))
        if (catalog != null) catalog.extras.forEach { add(DiscoverField("x:${it.name}", addonFilterTitle(it.name))) }
        else listOf("sort" to "Ordenar por", "genres" to "Gêneros", "year" to "Ano", "cert" to "Classificação", "rating" to "Nota mínima")
            .forEach { add(DiscoverField(it.first, it.second)) }
    }
    val genres = state.genres.filter { it.id in state.selectedGenres }.map { it.name }
    val genreValue = when (genres.size) {
        0 -> "Todos"
        1 -> genres.first()
        else -> "${genres.first()} +${genres.size - 1}"
    }
    val nearEnd by remember {
        derivedStateOf {
            val info = grid.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 16
        }
    }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) viewModel.loadMore() }
    LaunchedEffect(state.loading) { if (state.loading) grid.scrollToItem(0) }
    LaunchedEffect(restoreFocus, picker) {
        val target = restoreFocus
        if (target != null && picker == null) {
            withFrameNanos { }
            runCatching { requester(target).requestFocus() }
            restoreFocus = null
        }
    }

    TvText {
        Column(Modifier.fillMaxSize().background(Background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = tvPad).padding(top = 2.r, bottom = 1.5.r),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    TvEyebrow("Explore o catálogo")
                    Text("Descobrir", color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.Bold,
                        fontSize = 2.8.rs, lineHeight = 3.2.rs, letterSpacing = (-0.08).rs)
                }
                // O addon não diz quantos títulos tem, então só o TMDB mostra a contagem.
                val count = if (state.loading) "Atualizando…" else if (catalog != null) "" else "%,d títulos".format(state.totalResults).replace(',', '.')
                if (count.isNotEmpty()) Text(count, color = TextSecondary, fontSize = 1.rs, modifier = Modifier.padding(end = 1.4.r))
                TvButton(if (clearing && state.loading) "Atualizando…" else "Limpar filtros",
                    { clearing = true; pendingField = null; viewModel.clearFilters() }, icon = Icons.Default.Refresh,
                    enabled = state.activeFilterCount > 0 && !state.loading, busy = clearing && state.loading)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = tvPad),
                horizontalArrangement = Arrangement.spacedBy(0.8.r),
            ) {
                fields.forEach { field ->
                    val id = field.id
                    val value = when {
                        id == "kind" -> if (movie) "Filmes" else "Séries"
                        id == "catalog" -> catalog?.name ?: TMDB_LABEL
                        id.startsWith("x:") -> state.addonFilters[id.removePrefix("x:")] ?: "Todos"
                        id == "sort" -> sorts.firstOrNull { it.id == state.sort }?.label ?: "Populares"
                        id == "genres" -> genreValue
                        id == "year" -> state.year?.toString() ?: "Todos"
                        id == "cert" -> if (!movie) "Para filmes" else when (state.cert) { "" -> "Todas"; "L" -> "Livre"; else -> "${state.cert} anos" }
                        else -> state.minRating?.let { "${it.toInt()} ou mais" } ?: "Todas"
                    }
                    val selected = when {
                        id == "kind" -> !movie
                        id == "catalog" -> catalog != null
                        id.startsWith("x:") -> state.extraValues.containsKey(id.removePrefix("x:"))
                        id == "sort" -> state.sort != DiscoverViewModel.SORT_POPULAR
                        id == "genres" -> state.selectedGenres.isNotEmpty()
                        id == "year" -> state.year != null
                        id == "cert" -> state.cert.isNotBlank()
                        else -> state.minRating != null
                    }
                    FilterSelector(
                        field.title, value, selected,
                        busy = state.loading && pendingField == id,
                        enabled = (id != "cert" || movie) && (id != "genres" || state.genres.isNotEmpty()),
                        modifier = Modifier.weight(if (id == "catalog") 1.7f else if (id == "sort" || id == "genres") 1.2f else 1f)
                            .focusRequester(requester(id)),
                        onClick = { picker = id },
                    )
                }
            }
            val active = if (catalog != null) listOf("${catalog.name}  ·  ${catalog.addonName}") + state.extraValues.values
            else genres + listOfNotNull(
                state.year?.toString(), state.cert.takeIf { it.isNotBlank() }?.let { if (it == "L") "Livre" else "$it anos" },
                state.minRating?.let { "Nota ${it.toInt()} ou mais" },
            )
            Text(
                active.joinToString("  ·  ").ifBlank { "Escolha um filtro para encontrar o que você quer assistir" },
                color = if (active.isEmpty()) TextTertiary else Accent, fontSize = 0.95.rs, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = tvPad).padding(top = 1.r, bottom = 0.8.r),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> TvSpinner("Atualizando catálogo", Modifier.align(Alignment.Center))
                    state.items.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Search, null, tint = TextTertiary, modifier = Modifier.size(3.5.r))
                        Text(
                            when {
                                state.failed -> "O addon não respondeu"
                                state.unresolved -> "Nada para abrir aqui"
                                catalog != null -> "Catálogo vazio"
                                else -> "Nada com esses filtros"
                            },
                            color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.Bold,
                            fontSize = 1.7.rs, modifier = Modifier.padding(top = 1.r),
                        )
                        Text(
                            when {
                                state.failed -> "Confira a conexão ou escolha outro catálogo"
                                state.unresolved -> "Os títulos desse catálogo não existem no TMDB, o que é comum em catálogo de anime"
                                catalog != null -> "Tente outro filtro ou outro catálogo"
                                else -> "Tente outro gênero, ano ou classificação"
                            },
                            color = TextSecondary, fontSize = 1.1.rs, modifier = Modifier.padding(top = 0.5.r),
                        )
                    }
                    else -> TvScrollArea(1.r) {
                        LazyVerticalGrid(
                            // O pôster nunca fica menor que o da Início (mesma medida, com 3% de folga para manter as 8 colunas do tamanho
                            // padrão). Quantas colunas cabem depende da largura que o menu e o Tamanho da interface deixam.
                            GridCells.Adaptive(tvPosterWidth * 0.97f), state = grid,
                            contentPadding = PaddingValues(start = tvPad, end = tvPad, top = 0.7.r, bottom = 3.r),
                            horizontalArrangement = Arrangement.spacedBy(1.r), verticalArrangement = Arrangement.spacedBy(1.5.r),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.items, key = { "${it.type}:${it.tmdbId}" }) { item ->
                                TvPoster(item, { onItemClick(item) }, width = Dp.Unspecified)
                            }
                        }
                    }
                }
            }
        }
        picker?.let { id ->
            val field = fields.firstOrNull { it.id == id }
            // O campo some quando o catálogo muda por baixo (addon removido). O seletor fecha sem escrever estado na composição.
            if (field == null) { LaunchedEffect(id) { picker = null }; return@let }
            val multi = id == "genres"
            val options = when {
                id == "kind" -> listOf(FilterChoice("movie", "Filmes"), FilterChoice("series", "Séries"))
                id == "catalog" -> listOf(FilterChoice("", TMDB_LABEL, TMDB_SUB)) +
                    state.kindCatalogs.map { FilterChoice(it.catalogKey(), it.name, it.addonName) }
                id.startsWith("x:") -> catalog?.extras?.firstOrNull { it.name == id.removePrefix("x:") }?.let { e ->
                    (if (e.required) emptyList() else listOf(FilterChoice("", "Todos"))) + e.options.map { FilterChoice(it, it) }
                }.orEmpty()
                id == "sort" -> sorts
                id == "genres" -> state.genres.map { FilterChoice(it.id.toString(), it.name) }
                id == "year" -> listOf(FilterChoice("", "Todos os anos")) + (year downTo 1990).map { FilterChoice(it.toString(), it.toString()) }
                id == "cert" -> listOf(FilterChoice("", "Todas as idades")) + DiscoverViewModel.CERTS.map { FilterChoice(it, if (it == "L") "Livre" else "$it anos") }
                else -> listOf(FilterChoice("", "Todas as notas")) + (6..9).map { FilterChoice(it.toString(), "$it ou mais") }
            }
            val selected = when {
                id == "kind" -> setOf(if (movie) "movie" else "series")
                id == "catalog" -> setOf(if (catalog != null) state.catalogKey else "")
                id.startsWith("x:") -> setOf(state.addonFilters[id.removePrefix("x:")].orEmpty())
                id == "sort" -> setOf(state.sort)
                id == "genres" -> state.selectedGenres.map { it.toString() }.toSet()
                id == "year" -> setOf(state.year?.toString().orEmpty())
                id == "cert" -> setOf(state.cert)
                else -> setOf(state.minRating?.toInt()?.toString().orEmpty())
            }
            // Catálogo ocupa a linha inteira por causa do nome do addon. Lista longa de opções divide em mais colunas.
            val columns = when {
                multi -> 3
                id == "year" -> 4
                id == "catalog" -> 1
                id.startsWith("x:") -> if (options.size > 24) 4 else if (options.size > 8) 3 else 2
                else -> 2
            }
            fun close() { picker = null; restoreFocus = id }
            FilterPicker(field.title, multi, columns, options, selected, onDismiss = ::close) { values ->
                if (values == selected) { close(); return@FilterPicker }
                pendingField = id
                clearing = false
                val value = values.firstOrNull().orEmpty()
                when {
                    id == "kind" -> viewModel.setKind(if (value == "movie") DiscoverViewModel.Kind.MOVIE else DiscoverViewModel.Kind.SERIES)
                    id == "catalog" -> viewModel.selectCatalog(value)
                    id.startsWith("x:") -> viewModel.setExtra(id.removePrefix("x:"), value)
                    id == "sort" -> viewModel.setSort(value)
                    id == "genres" -> viewModel.setGenres(values.mapNotNull { it.toIntOrNull() }.toSet())
                    id == "year" -> if (value.toIntOrNull() != state.year) viewModel.setYear(value.toIntOrNull())
                    id == "cert" -> if (value != state.cert) viewModel.setCert(value)
                    else -> if (value.toFloatOrNull() != state.minRating) viewModel.setMinRating(value.toFloatOrNull())
                }
                close()
            }
        }
    }
}

@Composable
private fun FilterSelector(
    label: String, value: String, selected: Boolean, enabled: Boolean, busy: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(0.65.r)
    val scale = tvFocusScale(focused, 1.015f)
    Row(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }.height(4.7.r)
            .clip(shape).background(if (selected) AccentSoft else BackgroundCard)
            .border(if (focused) 0.18.r else 0.06.r, if (focused) Color.White else if (selected) Accent.copy(alpha = .55f) else Border, shape)
            .focusProperties { canFocus = enabled }.onFocusChanged { focused = it.isFocused }
            .tvClick({ if (!busy) onClick() }, enabled = enabled).padding(horizontal = 1.r),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), color = if (selected) Accent else TextTertiary, fontSize = 0.72.rs,
                fontWeight = FontWeight.Bold, fontFamily = DisplayFont, letterSpacing = 0.08.rs, maxLines = 1)
            Text(value, color = if (enabled) TextPrimary else TextTertiary, fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold, fontSize = 1.05.rs, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 0.45.r))
        }
        if (busy) com.joabtv.app.ui.common.SpinningIcon(tint = TextSecondary, size = 1.2.r)
        else Icon(Icons.Default.ExpandMore, null, tint = if (enabled) TextSecondary else TextTertiary, modifier = Modifier.padding(start = 0.4.r).size(1.2.r))
    }
}

@Composable
private fun FilterPicker(
    title: String, multi: Boolean, columns: Int, options: List<FilterChoice>, selected: Set<String>,
    onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit,
) {
    var draft by remember(title) { mutableStateOf(selected) }
    val cols = tvColumns(columns)
    val firstIndex = options.indexOfFirst { it.id in selected }.coerceAtLeast(0)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (firstIndex / cols) * cols)
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(title, options.size) { withFrameNanos { }; runCatching { first.requestFocus() } }
        Column(
            Modifier.width(62.r).heightIn(max = 39.r).clip(RoundedCornerShape(1.r))
                .background(BackgroundElevated).border(0.06.r, Border, RoundedCornerShape(1.r)).padding(1.8.r),
        ) {
            TvEyebrow(if (multi) "Selecione os gêneros que você gosta" else "Refine sua busca")
            Text(title, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.Bold,
                fontSize = 2.rs, modifier = Modifier.padding(bottom = 1.2.r))
            TvScrollArea(0.5.r) {
                LazyVerticalGrid(
                    GridCells.Fixed(cols), state = grid, modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(0.6.r), verticalArrangement = Arrangement.spacedBy(0.6.r),
                    contentPadding = PaddingValues(0.3.r),
                ) {
                    items(options, key = { it.id }) { option ->
                        var focused by remember { mutableStateOf(false) }
                        val on = option.id in draft
                        val shape = RoundedCornerShape(0.55.r)
                        Row(
                            Modifier.height(if (option.sub != null) 4.6.r else 4.r).clip(shape).background(if (on) AccentSoft else Surface2)
                                .border(if (focused) 0.16.r else 0.06.r, if (focused) Color.White else if (on) Accent else Color.Transparent, shape)
                                .then(if (options.indexOf(option) == firstIndex) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { focused = it.isFocused }
                                .tvClick({
                                    if (multi) draft = if (on) draft - option.id else draft + option.id
                                    else onConfirm(setOf(option.id))
                                }).padding(horizontal = 0.9.r),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(option.label, color = TextPrimary, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold,
                                    fontSize = 1.rs, maxLines = if (option.sub != null) 1 else 2, overflow = TextOverflow.Ellipsis)
                                if (option.sub != null) Text(option.sub, color = TextTertiary, fontFamily = DisplayFont,
                                    fontSize = 0.8.rs, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 0.2.r))
                            }
                            if (on) Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.padding(start = 0.5.r).size(1.2.r))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 1.2.r), horizontalArrangement = Arrangement.spacedBy(0.6.r, Alignment.End)) {
                TvButton("Cancelar", onDismiss)
                if (multi) {
                    TvButton("Limpar seleção", { draft = emptySet() })
                    TvButton("Aplicar gêneros", { onConfirm(draft) }, primary = true)
                }
            }
        }
    }
}
