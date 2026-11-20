package com.hcode.ipswtoolkit

/**
 * Curated, historical publicly documented root-filesystem VFDecrypt keys.
 * Source: The Apple Wiki (CC BY-SA), https://theapplewiki.com/wiki/Firmware_Keys
 * These keys apply ONLY to the model, version, build, and DMG named in the record.
 * Not a complete database of every iOS version.
 */
object FirmwareKeys {
    data class Model(val label: String, val identifier: String)
    data class Record(
        val model: String,
        val version: String,
        val build: String,
        val filesystem: String,
        val key: String,
        val sourcePage: String
    ) {
        val identity: String get() = model + "|" + version + "|" + build + "|" + filesystem.lowercase()
    }
    val models = listOf(
        Model("iPhone (original / 2G)", "iPhone1,1"),
        Model("iPhone 3G", "iPhone1,2"),
        Model("iPhone 3GS", "iPhone2,1"),
        Model("iPhone 4 (GSM)", "iPhone3,1"),
        Model("iPhone 4 (GSM Rev A)", "iPhone3,2"),
        Model("iPhone 4 (CDMA)", "iPhone3,3"),
        Model("iPhone 4S", "iPhone4,1"),
        Model("iPod touch (1st generation)", "iPod1,1"),
        Model("iPod touch (2nd generation)", "iPod2,1"),
        Model("iPod touch (3rd generation)", "iPod3,1"),
        Model("iPod touch (4th generation)", "iPod4,1")
    )
    private val rows = """
iPhone1,1|1.1.3|4A93|022-3743-100.dmg|11070c11d93b9be5069b643204451ed95aad37df7b332d10e48fd3d23c62fca517055816|LittleBear
iPhone1,1|1.1.4|4A102|022-3894-4.dmg|d0a0c0977bd4b6350b256d6650ec9eca419b6f961f593e74b7e5b93e010b698ca6cca1fe|LittleBear
iPhone1,1|2.0.1|5B108|018-3884-4.dmg|2cfca55aabb22fde7746e6a034f738b7795458be9902726002a8341995558990f41e3755|BigBear
iPhone1,1|3.1.3|7E18|018-6482-014.dmg|3c0f821663316c08a0a059c2979ecf47d13b363de3a44010d0de0b0a5cf878cfe39d00c3|SUNorthstarTwo
iPhone1,2|2.0.1|5B108|018-3880-4.dmg|2cfca55aabb22fde7746e6a034f738b7795458be9902726002a8341995558990f41e3755|BigBear
iPhone1,2|2.0.2|5C1|018-3978-1.dmg|31e3ff09ff046d5237187346ee893015354d2135e3f0f39480be63dd2a18444961c2da5d|BigBear
iPhone1,2|3.1.3|7E18|018-6482-014.dmg|bf5eb72cd65e9c37cf9920707cb6b4f7ecc10b38cfec6b167002ac9fd6a3ab6643e45005|SUNorthstarTwo
iPhone1,2|4.2.1|8C148|038-0015-002.dmg|82c9280927224637c77a96a26d22f42f2ca08fa9a798a8d06fbc8202fc83ec7f45dda79e|Jasper
iPhone2,1|3.0|7A341|018-5302-002.dmg|7d779fed28961506ca9443de210224f211790192b2a2308b8bc0e7d4a2ca61a68e26200e|Kirkwood
iPhone2,1|3.1.3|7E18|018-6483-022.dmg|9b3fd35bad7d5307d85ce4b38b8e56bd680ef5a72a8f3b615f8d4f16c14bdcf3c3b24c6c|SUNorthstarTwo
iPhone3,1|4.0|8A293|018-6303-385.dmg|8b2915719d9f90ba5521faad1eadbb3d942991bd55e5a0709f26e9db3931517e054afa50|Apex
iPhone3,1|4.2.1|8C148|038-0019-002.dmg|b2ee5018ef7d02e45ef67449d9e2ed5f876efae949de64a9a93dbcf7ff9ed84e041e9167|Jasper
iPhone3,2|6.0|10A403|038-6497-001.dmg|6055317e3c3f6021c8e0b9e1daa26ec387d2e798c9ee8cf0ec3150665b049ef6d4c10e67|Sundance
iPhone3,2|6.0.1|10A523|038-7956-010.dmg|f5705c4d5d7ce7972ab915e62cac8096e254c3bf3635d7d205eab868d447959135d06c7a|Sundance
iPhone3,2|6.1.3|10B329|048-2727-005.dmg|7e2dbf591820349a41df18e39e18b351422e2ca424f3cba61709d7384545f5d8dd7674de|BrightonMaps
iPhone3,2|7.1.2|11D257|058-4498-010.dmg|10a8d34290659b33117f5b6dfe59055d6a38400819dbbecfd3ad50b7b68619c5bf10f852|Sochi
iPhone3,3|4.2.6|8E200|038-0520-001.dmg|723ded674deb1cba56a142542a0b06d2a483297f8056c0cfa70346c0724e1b0e03feded6|Phoenix
iPhone4,1|5.0.1|9A406|038-3763-001.dmg|a31ffd506c6711c5a0c52c9f0a2f7208a2f63ad9dd40506e70d80ea20a981eb1312bc774|Telluride
iPod1,1|1.1.5|4B1|018-3755-1.dmg|c7973558e8f6af22e38d4573737d1533f1d5eec202bf86a32d941975d76f8906c7f0afe4|LittleBear
iPod1,1|2.0.1|5B108|018-3796-9.dmg|2cfca55aabb22fde7746e6a034f738b7795458be9902726002a8341995558990f41e3755|BigBear
iPod1,1|2.1|5F137|018-4116-2.dmg|9714f2cb955afa550d6287a1c7dd7bd0efb3c26cf74b948de7c43cf934913df69fc5a05f|SugarBowl
iPod2,1|2.2.1|5H11a|018-4491-11.dmg|2611c9f73504344fb22c93791659ec92e65f914025c5814d708b2023ab67229d89c39791|SUTimberline
iPod2,1|3.1.2|7D11|018-6133-014.dmg|fc68c25f1dcc929f37c2be82b94e4c92b48eac3ddd67adefd462404663265e3dca43a930|Northstar
iPod3,1|3.1.2|7D11|018-6152-014.dmg|1e05ef21821280869d4029a2328836b9f60bc63907c6e951c0f1c80c2d8c66aef5c39a44|Northstar
iPod3,1|4.2.1|8C148|038-0061-002.dmg|abd68f16920490865a09e559123db1f471ff19743ad15ea8b970a73e28f5efc6c6e76925|Jasper
iPod4,1|4.1|8B117|018-7062-093.dmg|e7de54b25167afc66e381ade1d5e25c6392757497cfd92826a3111772731ba0b70742b90|Baker
iPod4,1|4.2.1|8C148|038-0017-002.dmg|982437b30d334c744c94b9a73ab70e0fc6ed94c181b2a8b0fde6ee03f2546cc9b2c5b01c|Jasper
    """.trimIndent()

    val builtIn: List<Record> = rows.lineSequence().map(String::trim).filter(String::isNotEmpty).map { line ->
        val fields = line.split("|")
        require(fields.size == 6) { "Malformed firmware catalogue record" }
        require(fields[4].length == 72 && fields[4].all { it in "0123456789abcdefABCDEF" }) {
            "Invalid firmware key for " + fields[0] + " " + fields[2]
        }
        Record(fields[0], fields[1], fields[2], fields[3], fields[4].lowercase(),
            "https://theapplewiki.com/wiki/Keys:" + fields[5] + "_" + fields[2] + "_(" + fields[0] + ")")
    }.toList()

    fun forModel(model: String, extra: List<Record> = emptyList()): List<Record> =
        (extra + builtIn).filter { it.model == model }
            .distinctBy { it.identity }
            .sortedWith(compareBy<Record> { it.version }.thenBy { it.build })

    fun exact(model: String, version: String, build: String, filename: String,
              extra: List<Record> = emptyList()): Record? =
        (extra + builtIn).firstOrNull {
            it.model == model && it.version == version && it.build.equals(build, true) &&
                it.filesystem.equals(filename.substringAfterLast('/'), true)
        }
}
