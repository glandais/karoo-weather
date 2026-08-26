package io.github.glandais.karoo.weather.weather

/** Loads a committed Open-Meteo capture from `app/src/test/resources/fixtures/`. */
object Fixtures {
    fun read(name: String): String {
        val stream =
            Fixtures::class.java.classLoader?.getResourceAsStream("fixtures/$name")
                ?: error("fixture not found: $name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
