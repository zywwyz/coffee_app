package com.niumi.coffeejournal.backup

internal object CoffeeDatabaseSchema {
    const val CURRENT = 3

    fun identityHash(version: Int): String = when (version) {
        1 -> "630300b58f2f33802ecc0d756158b804"
        2 -> "e34586f75354c95386a2ba92f7121b27"
        3 -> "f93d8a13b1b47c68acba071ab2cf88cf"
        else -> error("Unsupported schema version $version")
    }
}
