package com.mtracker.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalLaundryService
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- icons
val HEAD_ICON_KEYS: List<Pair<String, ImageVector>> = listOf(
    "food" to Icons.Rounded.Restaurant, "cafe" to Icons.Rounded.LocalCafe, "fastfood" to Icons.Rounded.Fastfood,
    "grocery" to Icons.Rounded.ShoppingCart, "shop" to Icons.Rounded.ShoppingBag, "cloth" to Icons.Rounded.Checkroom,
    "car" to Icons.Rounded.DirectionsCar, "bus" to Icons.Rounded.DirectionsBus, "fuel" to Icons.Rounded.LocalGasStation,
    "flight" to Icons.Rounded.Flight, "home" to Icons.Rounded.Home, "bolt" to Icons.Rounded.Bolt,
    "bill" to Icons.Rounded.Receipt, "phone" to Icons.Rounded.PhoneAndroid, "wifi" to Icons.Rounded.Wifi,
    "tools" to Icons.Rounded.Handyman, "movie" to Icons.Rounded.Movie, "tv" to Icons.Rounded.Tv,
    "music" to Icons.Rounded.MusicNote, "games" to Icons.Rounded.SportsEsports, "party" to Icons.Rounded.Celebration,
    "subs" to Icons.Rounded.Subscriptions, "spa" to Icons.Rounded.Spa, "health" to Icons.Rounded.HealthAndSafety,
    "meds" to Icons.Rounded.Medication, "fitness" to Icons.Rounded.FitnessCenter, "pets" to Icons.Rounded.Pets,
    "kids" to Icons.Rounded.ChildCare, "school" to Icons.Rounded.School, "book" to Icons.Rounded.MenuBook,
    "pencil" to Icons.Rounded.Create, "gift" to Icons.Rounded.CardGiftcard, "donate" to Icons.Rounded.VolunteerActivism,
    "invest" to Icons.Rounded.TrendingUp, "bank" to Icons.Rounded.AccountBalance, "card" to Icons.Rounded.CreditCard,
    "savings" to Icons.Rounded.Savings, "income" to Icons.Rounded.Payments, "work" to Icons.Rounded.Work,
    "refund" to Icons.Rounded.Replay, "reward" to Icons.Rounded.Redeem, "swap" to Icons.Rounded.SwapHoriz,
    "water" to Icons.Rounded.WaterDrop, "gas" to Icons.Rounded.LocalFireDepartment, "laundry" to Icons.Rounded.LocalLaundryService,
    "person" to Icons.Rounded.Person, "other" to Icons.Rounded.MoreHoriz, "tag" to Icons.Rounded.Label
)
val HEAD_ICON_MAP: Map<String, ImageVector> = HEAD_ICON_KEYS.toMap()

/** Picks a sensible icon from a head's name, so new heads look right without any setup. */
fun guessIconKey(name: String): String {
    val n = name.lowercase()
    return when {
        "cylinder" in n -> "gas"
        "water" in n -> "water"
        "laundry" in n -> "laundry"
        "vegetable" in n || "fruit" in n || "milk" in n || "dairy" in n -> "grocery"
        n.startsWith("emi") || "loan" in n -> "bank"
        "medicine" in n -> "meds"
        "stipend" in n || "freelance" in n || "pocket" in n -> "income"
        "dividend" in n || "cashback" in n -> "reward"
        "household" in n -> "home"
        "coffee" in n || "cafe" in n || "snack" in n -> "cafe"
        "dining" in n || "food" in n || "restaurant" in n || "meal" in n -> "food"
        "grocer" in n -> "grocery"
        "cloth" in n || "fashion" in n -> "cloth"
        "shop" in n -> "shop"
        "fuel" in n || "gas" in n || "petrol" in n -> "fuel"
        "transport" in n || "auto" in n || "cab" in n || "taxi" in n || "ride" in n -> "car"
        "metro" in n || "train" in n -> "bus"
        "travel" in n || "flight" in n || "trip" in n -> "flight"
        "rent" in n || "home & rep" in n || "house" in n -> "home"
        "repair" in n || "maint" in n -> "tools"
        "bill" in n || "utilit" in n || "electric" in n -> "bolt"
        "phone" in n || "recharge" in n || "mobile" in n -> "phone"
        "internet" in n || "wifi" in n -> "wifi"
        "entertain" in n || "movie" in n -> "movie"
        "subscr" in n -> "subs"
        "game" in n -> "games"
        "personal" in n || "care" in n || "salon" in n -> "spa"
        "health" in n || "insur" in n || "doctor" in n || "medic" in n -> "health"
        "gym" in n || "fitness" in n -> "fitness"
        "pet" in n -> "pets"
        "kid" in n || "family" in n || "child" in n -> "kids"
        "educat" in n || "school" in n || "tuition" in n || "course" in n || "exam" in n -> "school"
        "book" in n -> "book"
        "station" in n -> "pencil"
        "gift" in n || "donat" in n || "charity" in n -> "gift"
        "invest" in n || "sip" in n || "stock" in n || "mutual" in n -> "invest"
        "tax" in n || "fee" in n -> "bank"
        "salary" in n || "stipend" in n || "income" in n || "wage" in n -> "income"
        "refund" in n -> "refund"
        "interest" in n || "saving" in n -> "savings"
        "transfer" in n -> "swap"
        "other" in n -> "other"
        else -> "tag"
    }
}

private val HEAD_HUES = listOf(
    0xFF7C9CFF, 0xFF5FD1B4, 0xFFF2B35E, 0xFFF08A7E, 0xFFB391F5,
    0xFF6CC3E8, 0xFFE58FBF, 0xFF9BCB6A, 0xFFE0C46A, 0xFF8A9BB0
).map { Color(it) }

fun headColor(name: String, dark: Boolean): Color {
    val c = HEAD_HUES[(name.lowercase().hashCode() and 0x7fffffff) % HEAD_HUES.size]
    return if (dark) c else lerp(c, Color.Black, 0.35f)
}

val CARD_COLORS = listOf(
    0xFF2D3B55, 0xFF1F4D4A, 0xFF5A3E2B, 0xFF4A2F5C, 0xFF1E4E3A,
    0xFF5C2B38, 0xFF33415C, 0xFF3E4A22, 0xFF5E4B1F, 0xFF2B2B2B
).map { Color(it) }
fun cardColor(i: Int): Color = CARD_COLORS[((i % CARD_COLORS.size) + CARD_COLORS.size) % CARD_COLORS.size]

/** name -> stored icon key for custom heads; provided once at the top of the app. */
val LocalHeadIcons = compositionLocalOf<Map<String, String>> { emptyMap() }

@Composable
fun HeadTile(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier, stored: String? = null) {
    val key = (stored ?: LocalHeadIcons.current[name])?.takeIf { it in HEAD_ICON_MAP } ?: guessIconKey(name)
    val col = headColor(name, L.dark)
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(col.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(HEAD_ICON_MAP.getValue(key), contentDescription = null, tint = col, modifier = Modifier.size(size * 0.55f))
    }
}

// ---------------------------------------------------------------- quick strip
@Composable
fun HeadChoice(name: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.then(if (selected) Modifier.border(2.dp, L.text, RoundedCornerShape(18.dp)) else Modifier).padding(3.dp)
        ) { HeadTile(name, 52.dp) }
        Spacer(Modifier.height(4.dp))
        Text(
            name, color = if (selected) L.text else L.muted, fontSize = 11.sp, maxLines = 2, lineHeight = 13.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

/** A small pinned control that never scrolls away: top half = add new, bottom half = see more (opens the tray). */
@Composable
fun PinnedPair(onNew: () -> Unit, onMore: () -> Unit, height: Dp = 48.dp) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.padding(start = 8.dp).width(46.dp).height(height).clip(shape).border(1.dp, L.border, shape)) {
        Box(Modifier.weight(1f).fillMaxWidth().clickable(onClick = onNew), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "New", tint = L.text, modifier = Modifier.size(18.dp))
        }
        Hairline()
        Box(Modifier.weight(1f).fillMaxWidth().clickable(onClick = onMore), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MoreHoriz, "More", tint = L.text, modifier = Modifier.size(18.dp))
        }
    }
}

/** Your four regular heads, then the pinned New / More control. Nothing is preselected. */
@Composable
fun HeadStrip(names: List<String>, selected: String, onPick: (String) -> Unit, onNew: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            names.take(4).forEach { n -> HeadChoice(n, n == selected, Modifier.width(66.dp)) { onPick(n) } }
        }
        Box(Modifier.padding(top = 12.dp)) { PinnedPair(onNew, onMore, 52.dp) }
    }
}

/** Every merchant you've used, searchable; type a new name to add it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantsSheet(names: List<String>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = names.filter { q.isBlank() || it.contains(q.trim(), ignoreCase = true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = L.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Cap("Merchants", color = L.text)
            Field("Search or add new", q, { q = it })
        }
        LazyColumn(Modifier.heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (q.isNotBlank() && names.none { it.equals(q.trim(), true) }) item(key = "__new") {
                Text("+ Add “${q.trim()}”", color = L.text, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(q.trim()) }.padding(24.dp, 14.dp))
            }
            listItems(shown, key = { it }) { n ->
                Column(Modifier.clickable { onPick(n) }) {
                    Row(Modifier.fillMaxWidth().padding(24.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, color = L.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (n.equals(selected, true)) Text("✓", color = L.text, fontSize = 16.sp)
                    }
                    Hairline(Modifier.padding(start = 24.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the sliding tray
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeadsSheet(
    heads: List<Category>, initialKind: String, selected: String?, usage: Map<String, Int>,
    showKindSwitch: Boolean, label: String = "Choose a head", hint: String? = null,
    onPick: (String, String) -> Unit, onCreate: ((String, String, String) -> Unit)? = null, onDismiss: () -> Unit
) {
    var kind by remember { mutableStateOf(initialKind) }
    LaunchedEffect(initialKind) { kind = initialKind }   // a new intent can replace the pending draft
    var q by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val list = heads.filter { it.kind == kind }
    val names = list.map { it.name }
    val shown = list.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }.map { it.name }
    // "Other" stays last, so the + tile is always the final choice.
    val ordered = shown.sortedWith(compareBy<String> { it.equals("Other", ignoreCase = true) }.thenBy { shown.indexOf(it) })
    val popular = names.filter { !it.equals("Other", true) && (usage["$kind|$it"] ?: 0) > 0 }
        .sortedByDescending { usage["$kind|$it"] ?: 0 }.take(4)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = L.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 4.dp).width(36.dp).height(4.dp).clip(CircleShape).background(L.border))
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (hint != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, color = L.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        InfoTip(hint)
                    }
                } else Cap(label, color = L.text)
                if (showKindSwitch) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Expense", kind == "EXPENSE") { kind = "EXPENSE" }
                    Chip("Income", kind == "INCOME") { kind = "INCOME" }
                }
            }
            Field("Search heads", q, { q = it })
        }
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 560.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (q.isBlank() && popular.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hp") { Cap("Most used", Modifier.padding(8.dp, 12.dp, 8.dp, 2.dp)) }
                items(popular, key = { "p$it" }) { n -> HeadChoice(n, n == selected) { onPick(n, kind) } }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "ha") { Cap("All heads", Modifier.padding(8.dp, 12.dp, 8.dp, 2.dp)) }
            items(ordered, key = { "a$it" }) { n -> HeadChoice(n, n == selected) { onPick(n, kind) } }
            if (onCreate != null) item(key = "new") {
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).clickable { creating = true }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.padding(3.dp).size(52.dp).clip(RoundedCornerShape(17.dp)).border(1.dp, L.border, RoundedCornerShape(17.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("+", color = L.text, fontSize = 26.sp) }
                    Spacer(Modifier.height(4.dp))
                    Text("New", color = L.muted, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    onCreate?.let { create ->
        if (creating) NewHeadDialog(kind, { n, k, ic -> creating = false; create(n, k, ic) }, { creating = false })
    }
}

@Composable
fun NewHeadDialog(defaultKind: String, onCreate: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(defaultKind) }
    var icon by remember { mutableStateOf("") }
    val key = icon.ifEmpty { guessIconKey(name) }
    val shape = RoundedCornerShape(12.dp)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = L.surface, shape = RoundedCornerShape(10.dp),
        title = { Cap("New head", color = L.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeadTile(name.ifBlank { "Preview" }, 52.dp, stored = key)
                    Spacer(Modifier.width(14.dp))
                    Field("Name", name, { name = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Expense", kind == "EXPENSE") { kind = "EXPENSE" }
                    Chip("Income", kind == "INCOME") { kind = "INCOME" }
                }
                Cap("Icon")
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    HEAD_ICON_KEYS.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (k, v) ->
                                Box(
                                    Modifier.size(44.dp).clip(shape)
                                        .border(if (k == key) 2.dp else 1.dp, if (k == key) L.text else L.border, shape)
                                        .clickable { icon = k },
                                    contentAlignment = Alignment.Center
                                ) { Icon(v, null, tint = L.text, modifier = Modifier.size(22.dp)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) onCreate(name.trim(), kind, key) }) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
