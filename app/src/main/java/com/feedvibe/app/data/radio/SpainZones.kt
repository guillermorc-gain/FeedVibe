package com.feedvibe.app.data.radio

/** Un sitio (isla, provincia o ciudad) con sus coordenadas, para pedir sus emisoras locales. */
data class Place(val name: String, val lat: Double, val lon: Double)

/** Comunidad autónoma con sus islas o provincias. */
data class Region(val name: String, val places: List<Place>)

/** Comunidades autónomas y sus zonas (las emisoras se piden por cercanía a cada una). */
val SPAIN_REGIONS = listOf(
    Region("Andalucía", listOf(
        Place("Almería", 36.84, -2.46), Place("Cádiz", 36.53, -6.29), Place("Córdoba", 37.88, -4.78), Place("Granada", 37.18, -3.60),
        Place("Huelva", 37.26, -6.95), Place("Jaén", 37.78, -3.79), Place("Málaga", 36.72, -4.42), Place("Sevilla", 37.39, -5.98),
    )),
    Region("Aragón", listOf(Place("Huesca", 42.14, -0.41), Place("Teruel", 40.34, -1.11), Place("Zaragoza", 41.65, -0.89))),
    Region("Asturias", listOf(Place("Oviedo", 43.36, -5.85), Place("Gijón", 43.53, -5.66))),
    Region("Illes Balears", listOf(
        Place("Mallorca", 39.57, 2.65), Place("Menorca", 39.89, 4.27), Place("Ibiza", 38.91, 1.42), Place("Formentera", 38.70, 1.45),
    )),
    Region("Canarias", listOf(
        Place("Gran Canaria", 28.12, -15.43), Place("Tenerife", 28.47, -16.25), Place("Lanzarote", 28.96, -13.55),
        Place("Fuerteventura", 28.50, -13.86), Place("La Palma", 28.68, -17.76), Place("La Gomera", 28.09, -17.11), Place("El Hierro", 27.81, -17.91),
    )),
    Region("Cantabria", listOf(Place("Santander", 43.46, -3.80), Place("Torrelavega", 43.35, -4.05))),
    Region("Castilla-La Mancha", listOf(
        Place("Albacete", 38.99, -1.86), Place("Ciudad Real", 38.99, -3.93), Place("Cuenca", 40.07, -2.13),
        Place("Guadalajara", 40.63, -3.17), Place("Toledo", 39.86, -4.03),
    )),
    Region("Castilla y León", listOf(
        Place("Ávila", 40.66, -4.70), Place("Burgos", 42.34, -3.70), Place("León", 42.60, -5.57), Place("Palencia", 42.01, -4.53),
        Place("Salamanca", 40.97, -5.66), Place("Segovia", 40.95, -4.12), Place("Soria", 41.76, -2.46), Place("Valladolid", 41.65, -4.72),
        Place("Zamora", 41.50, -5.75),
    )),
    Region("Cataluña", listOf(Place("Barcelona", 41.39, 2.17), Place("Girona", 41.98, 2.82), Place("Lleida", 41.62, 0.62), Place("Tarragona", 41.12, 1.25))),
    Region("Comunidad Valenciana", listOf(Place("Alicante", 38.35, -0.48), Place("Castellón", 39.99, -0.05), Place("Valencia", 39.47, -0.38))),
    Region("Extremadura", listOf(Place("Badajoz", 38.88, -6.97), Place("Cáceres", 39.47, -6.37))),
    Region("Galicia", listOf(
        Place("A Coruña", 43.36, -8.41), Place("Lugo", 43.01, -7.56), Place("Ourense", 42.34, -7.86),
        Place("Pontevedra", 42.43, -8.64), Place("Vigo", 42.24, -8.72),
    )),
    Region("Comunidad de Madrid", listOf(Place("Madrid", 40.42, -3.70), Place("Alcalá de Henares", 40.48, -3.36))),
    Region("Región de Murcia", listOf(Place("Murcia", 37.99, -1.13), Place("Cartagena", 37.61, -0.99), Place("Lorca", 37.68, -1.70))),
    Region("Navarra", listOf(Place("Pamplona", 42.81, -1.65), Place("Tudela", 42.06, -1.61))),
    Region("País Vasco", listOf(Place("Álava", 42.85, -2.67), Place("Bizkaia", 43.26, -2.93), Place("Gipuzkoa", 43.32, -1.98))),
    Region("La Rioja", listOf(Place("Logroño", 42.47, -2.45))),
    Region("Ceuta", listOf(Place("Ceuta", 35.89, -5.32))),
    Region("Melilla", listOf(Place("Melilla", 35.29, -2.94))),
)
