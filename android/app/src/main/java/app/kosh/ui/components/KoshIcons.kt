package app.kosh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Bed
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DeliveryDining
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.RiceBowl
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Soap
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TempleHindu
import androidx.compose.material.icons.rounded.Toys
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icon keys are platform-neutral strings stored in records (see
 * server/internal/seed/default-categories.json); each client maps them to its icon set.
 */
object KoshIcons {
    val byKey: Map<String, ImageVector> = linkedMapOf(
        "home" to Icons.Rounded.Home, "key" to Icons.Rounded.Key, "bank" to Icons.Rounded.AccountBalance,
        "tools" to Icons.Rounded.Build, "receipt" to Icons.Rounded.Receipt, "apartment" to Icons.Rounded.Apartment,
        "bolt" to Icons.Rounded.Bolt, "water" to Icons.Rounded.WaterDrop, "flame" to Icons.Rounded.LocalFireDepartment,
        "wifi" to Icons.Rounded.Wifi, "phone" to Icons.Rounded.PhoneAndroid, "tv" to Icons.Rounded.Tv,
        "restaurant" to Icons.Rounded.Restaurant, "cart" to Icons.Rounded.ShoppingCart,
        "delivery" to Icons.Rounded.DeliveryDining, "coffee" to Icons.Rounded.LocalCafe, "bowl" to Icons.Rounded.RiceBowl,
        "car" to Icons.Rounded.DirectionsCar, "fuel" to Icons.Rounded.LocalGasStation, "bus" to Icons.Rounded.DirectionsBus,
        "taxi" to Icons.Rounded.LocalTaxi, "parking" to Icons.Rounded.LocalParking, "plane" to Icons.Rounded.Flight,
        "map" to Icons.Rounded.Map, "bed" to Icons.Rounded.Bed, "passport" to Icons.Rounded.Badge,
        "movie" to Icons.Rounded.Movie, "ticket" to Icons.Rounded.ConfirmationNumber,
        "gamepad" to Icons.Rounded.SportsEsports, "shirt" to Icons.Rounded.Checkroom, "bag" to Icons.Rounded.ShoppingBag,
        "devices" to Icons.Rounded.Devices, "sofa" to Icons.Rounded.Weekend, "spa" to Icons.Rounded.Spa,
        "scissors" to Icons.Rounded.ContentCut, "soap" to Icons.Rounded.Soap, "fitness" to Icons.Rounded.FitnessCenter,
        "health" to Icons.Rounded.LocalHospital, "stethoscope" to Icons.Rounded.MedicalServices,
        "pill" to Icons.Rounded.Medication, "heart" to Icons.Rounded.Favorite, "school" to Icons.Rounded.School,
        "book" to Icons.AutoMirrored.Rounded.MenuBook, "baby" to Icons.Rounded.ChildCare,
        "family" to Icons.Rounded.FamilyRestroom, "toy" to Icons.Rounded.Toys, "paw" to Icons.Rounded.Pets,
        "broom" to Icons.Rounded.CleaningServices, "shield" to Icons.Rounded.Shield, "percent" to Icons.Rounded.Percent,
        "gift" to Icons.Rounded.CardGiftcard, "temple" to Icons.Rounded.TempleHindu, "briefcase" to Icons.Rounded.Work,
        "laptop" to Icons.Rounded.Laptop, "code" to Icons.Rounded.Code, "chart" to Icons.AutoMirrored.Rounded.ShowChart,
        "wallet" to Icons.Rounded.AccountBalanceWallet, "undo" to Icons.AutoMirrored.Rounded.Undo,
        "repeat" to Icons.Rounded.Repeat, "star" to Icons.Rounded.Star, "plus" to Icons.Rounded.Add,
        "dots" to Icons.Rounded.MoreHoriz, "palette" to Icons.Rounded.Palette, "paperclip" to Icons.Rounded.AttachFile,
        "cash" to Icons.Rounded.Payments, "card" to Icons.Rounded.CreditCard, "savings" to Icons.Rounded.Savings,
        "invest" to Icons.AutoMirrored.Rounded.TrendingUp, "transfer" to Icons.Rounded.SwapHoriz,
        "split" to Icons.AutoMirrored.Rounded.CallSplit, "tag" to Icons.Rounded.Sell,
    )

    fun of(key: String?): ImageVector = key?.let { byKey[it] } ?: Icons.Rounded.MoreHoriz

    /** Default icon for an account type. */
    fun forAccountType(type: String): String = when (type) {
        "cash" -> "cash"
        "bank" -> "bank"
        "wallet" -> "wallet"
        "credit_card" -> "card"
        "savings" -> "savings"
        "investment" -> "invest"
        "loan" -> "percent"
        else -> "dots"
    }

    /** A palette for user-chosen colours (good contrast in light and dark themes). */
    val palette = listOf(
        "#EF6C00", "#E53935", "#D81B60", "#8E24AA", "#5E35B1", "#3949AB", "#1E88E5", "#039BE5",
        "#00ACC1", "#00897B", "#2E7D32", "#7CB342", "#C0CA33", "#FFB300", "#8D6E63", "#546E7A",
    )
}

fun parseColor(hex: String?, fallback: Color): Color = runCatching {
    if (hex == null || hex.length != 7 || hex[0] != '#') fallback else Color(0xFF000000 or hex.substring(1).toLong(16))
}.getOrDefault(fallback)

/** A coloured circle with an icon: the visual identity of a category or account. */
@Composable
fun IconBadge(icon: String?, color: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val c = parseColor(color, MaterialTheme.colorScheme.primary)
    Box(
        modifier.size(size).background(c.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(KoshIcons.of(icon), contentDescription = null, tint = c, modifier = Modifier.size(size * 0.55f))
    }
}
