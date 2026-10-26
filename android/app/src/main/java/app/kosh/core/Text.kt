package app.kosh.core

/** "1 day", "3 days": a count with its noun in the right number. */
fun plural(n: Number, one: String, many: String = one + "s"): String = "$n ${if (n.toLong() == 1L) one else many}"
