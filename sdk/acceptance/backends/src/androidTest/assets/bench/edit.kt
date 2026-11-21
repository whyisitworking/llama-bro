package com.example.ledger

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One line of a receipt: what was bought, how many, and the price of each in cents. */
data class LineItem(val name: String, val quantity: Int, val unitCents: Long) {
    val totalCents: Long get() = quantity * unitCents
}

/** A receipt as the user filed it. */
data class Receipt(
    val id: String,
    val shop: String,
    val date: LocalDate,
    val items: List<LineItem>,
    val currency: String = "EUR",
) {
    val totalCents: Long get() = items.sumOf { it.totalCents }
}

/** How one person's share of a receipt is worked out. */
sealed interface Split {
    data object Even : Split

    data class Weights(val weights: Map<String, Int>) : Split
}

private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE

/** Parses `name;quantity;price` where the price is written in whole and fractional units, such as `3.40`. */
fun parseLine(line: String): LineItem? {
    val parts = line.split(';').map { it.trim() }
    if (parts.size != 3) return null
    val quantity = parts[1].toIntOrNull() ?: return null
    val cents = parsePrice(parts[2]) ?: return null
    return LineItem(parts[0], quantity, cents)
}

/** `3.40` is 340 cents; `3` is 300; `3.4` is 340; more than two decimals are refused. */
fun parsePrice(text: String): Long? {
    val pieces = text.split('.')
    if (pieces.size > 2) return null
    val whole = pieces[0].toLongOrNull() ?: return null
    val fraction = pieces.getOrNull(1) ?: return whole * 100
    if (fraction.length > 2 || fraction.any { !it.isDigit() }) return null
    return whole * 100 + fraction.padEnd(2, '0').toLong()
}

/** Reads a whole receipt: a header of `shop;date;currency`, then one item per line. Bad lines are skipped. */
fun parseReceipt(id: String, text: String): Receipt? {
    val lines = text.lines().filter { it.isNotBlank() }
    val header = lines.firstOrNull()?.split(';')?.map { it.trim() } ?: return null
    if (header.size < 2) return null
    val date = runCatching { LocalDate.parse(header[1], dateFormat) }.getOrNull() ?: return null
    val currency = header.getOrNull(2)?.ifEmpty { null } ?: "EUR"
    val items = lines.drop(1).mapNotNull { parseLine(it) }
    return Receipt(id, header[0], date, items, currency)
}

/** The shares, in cents, of [total] among [people]; the cents that do not divide go to the first people in order. */
fun shares(total: Long, people: List<String>, split: Split): Map<String, Long> {
    require(people.isNotEmpty()) { "nobody to split among" }
    val weights =
        when (split) {
            Split.Even -> people.associateWith { 1 }
            is Split.Weights -> people.associateWith { split.weights[it] ?: 0 }
        }
    val sum = weights.values.sum()
    require(sum > 0) { "the weights add up to nothing" }
    val base = weights.mapValues { (_, weight) -> total * weight / sum }
    var left = total - base.values.sum()
    val result = base.toMutableMap()
    for (person in people) {
        if (left == 0L) break
        if ((weights[person] ?: 0) > 0) {
            result[person] = result.getValue(person) + 1
            left--
        }
    }
    return result
}

/** One month's spending, per shop, largest first. */
fun spendingByShop(receipts: List<Receipt>, year: Int, month: Int): List<Pair<String, Long>> =
    receipts
        .filter { it.date.year == year && it.date.monthValue == month }
        .groupBy { it.shop }
        .mapValues { (_, shopReceipts) -> shopReceipts.sumOf { it.totalCents } }
        .toList()
        .sortedByDescending { it.second }

/** The difference between two months, per shop, the biggest change first; a shop in one month only counts as zero. */
fun compareMonths(
    receipts: List<Receipt>,
    first: Pair<Int, Int>,
    second: Pair<Int, Int>,
): List<Triple<String, Long, Long>> {
    val a = spendingByShop(receipts, first.first, first.second).toMap()
    val b = spendingByShop(receipts, second.first, second.second).toMap()
    return (a.keys + b.keys)
        .map { Triple(it, a[it] ?: 0L, b[it] ?: 0L) }
        .sortedByDescending { kotlin.math.abs(it.third - it.second) }
}

/** A price for a person to read, such as `12.05 EUR`. */
fun formatCents(cents: Long, currency: String): String {
    val sign = if (cents < 0) "-" else ""
    val absolute = kotlin.math.abs(cents)
    return "$sign${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')} $currency"
}

/** One line per receipt for a plain-text report. */
fun reportLines(receipts: List<Receipt>): List<String> =
    receipts
        .sortedBy { it.date }
        .map { "${it.date.format(dateFormat)}  ${it.shop.padEnd(20)}  ${formatCents(it.totalCents, it.currency)}" }

/** The receipts of one shop, newest first. */
fun receiptsOfShop(receipts: List<Receipt>, shop: String): List<Receipt> =
    receipts.filter { it.shop.equals(shop, ignoreCase = true) }.sortedByDescending { it.date }

/** The most expensive item of a receipt, or null for a receipt with none. */
fun dearestItem(receipt: Receipt): LineItem? = receipt.items.maxByOrNull { it.unitCents }

/** The mean receipt total in cents, or zero when there are no receipts. */
fun averageTotal(receipts: List<Receipt>): Long =
    if (receipts.isEmpty()) 0L else receipts.sumOf { it.totalCents } / receipts.size

/** The receipts filed in the last [days] days before [today]. */
fun recentReceipts(receipts: List<Receipt>, today: LocalDate, days: Long): List<Receipt> =
    receipts.filter { !it.date.isBefore(today.minusDays(days)) && !it.date.isAfter(today) }

/** The receipts that have an item priced at zero, which is almost always a typing mistake. */
fun suspiciousReceipts(receipts: List<Receipt>): List<Receipt> =
    receipts.filter { receipt -> receipt.items.any { it.unitCents == 0L } }

/** The shops that appear on at least [times] receipts, in alphabetical order. */
fun regularShops(receipts: List<Receipt>, times: Int): List<String> =
    receipts.groupingBy { it.shop }.eachCount().filterValues { it >= times }.keys.sorted()
