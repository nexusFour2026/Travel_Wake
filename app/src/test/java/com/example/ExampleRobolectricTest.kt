package com.example

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.example.engine.DynamicETAEngine
import com.example.model.ActiveTrip
import com.example.model.AlarmStage
import com.example.model.DestinationSnapshot
import com.example.model.DestinationType
import com.example.model.MovementState
import com.example.model.TransportMode
import com.example.model.TripState
import com.example.ui.Localization
import com.example.ui.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read app_name from context matches TravelWake`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("TravelWake", appName)
    }

    @Test
    fun `localization returns correct translations for English, Tamil, and Sinhala`() {
        val enTitle = Localization.get(Strings.APP_NAME, "en")
        val taTitle = Localization.get(Strings.APP_NAME, "ta")
        val siTitle = Localization.get(Strings.APP_NAME, "si")

        assertEquals("TravelWake", enTitle)
        assertEquals("டிராவல்வேக்", taTitle)
        assertEquals("ට්‍රැවල්වේක්", siTitle)

        // Wake alert string test
        val enWake = Localization.get(Strings.WAKE_ME_NOW, "en")
        val taWake = Localization.get(Strings.WAKE_ME_NOW, "ta")
        val siWake = Localization.get(Strings.WAKE_ME_NOW, "si")

        assertEquals("WAKE ME NOW", enWake)
        assertEquals("இப்போதே எழுப்பு", taWake)
        assertEquals("දැන් අවදි කරන්න", siWake)
    }

    @Test
    fun `dynamic eta engine detects movement and arrival inside radius`() {
        val engine = DynamicETAEngine()
        val dest = DestinationSnapshot(
            name = "Central Station",
            latitude = 6.9271,
            longitude = 79.8612,
            type = DestinationType.RAILWAY_STATION,
            radiusMeters = 200f
        )

        val trip = ActiveTrip(
            destination = dest,
            alertMinutesBefore = 5,
            state = TripState.TRACKING
        )

        // 1. First location reading: 2km away moving at 40 km/h
        val loc1 = Location("gps").apply {
            latitude = 6.9100
            longitude = 79.8500
            speed = 40f / 3.6f
            accuracy = 15f
            time = 1000L
        }
        val calc1 = engine.processLocationUpdate(trip, loc1)
        assertTrue("Distance should be calculated", calc1.remainingDistanceMeters > 500)
        assertNotNull(calc1.remainingMinutes)

        // 2. Location reading inside destination radius (6.9271, 79.8612)
        val locArrival = Location("gps").apply {
            latitude = 6.9271
            longitude = 79.8612
            speed = 5f / 3.6f
            accuracy = 10f
            time = 2000L
        }
        val calc2 = engine.processLocationUpdate(trip, locArrival)
        assertTrue("Distance should be inside radius", calc2.remainingDistanceMeters <= 200f)
        assertEquals(1, calc2.consecutiveReadingsInsideRadius)

        // Consecutive reading inside confirms arrival
        val locArrival2 = Location("gps").apply {
            latitude = 6.9271
            longitude = 79.8612
            speed = 2f / 3.6f
            accuracy = 10f
            time = 3000L
        }
        val calc3 = engine.processLocationUpdate(trip, locArrival2)
        assertEquals(AlarmStage.ARRIVAL_CONFIRMED, calc3.recommendedAlarmStage)
    }

    @Test
    fun `wake-up alertness strings are loaded correctly`() {
        val holdEn = Localization.get(Strings.HOLD_TO_DISMISS, "en")
        val holdTa = Localization.get(Strings.HOLD_TO_DISMISS, "ta")
        val holdSi = Localization.get(Strings.HOLD_TO_DISMISS, "si")

        assertEquals("Hold to Dismiss & Verify Awake", holdEn)
        assertEquals("அழுத்திப் பிடித்து விழிப்பை உறுதிசெய்க", holdTa)
        assertEquals("අල්ලාගෙන සිට අවදි බව තහවුරු කරන්න", holdSi)
    }

    @Test
    fun `data preservation manager handles pre-migration backup gracefully`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backupResult = com.example.data.local.DataPreservationManager.createPreMigrationBackup(context)
        // Fresh install / test environment without DB file returns true (no-op)
        assertTrue(backupResult)
    }

    @Test
    fun `database migration preserves active trip and entities across schema updates`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = com.example.data.local.TravelWakeDatabase.getDatabase(context)

        // Insert saved place with new v2 fields
        val place = com.example.data.local.SavedPlaceEntity(
            id = "test_home_1",
            name = "University Hostel",
            type = DestinationType.UNIVERSITY,
            latitude = 6.9000,
            longitude = 79.8500,
            address = "Faculty Road, Colombo",
            radiusMeters = 250f,
            defaultAlertMinutes = 10,
            preferredTransportMode = TransportMode.BUS,
            notes = "Quiet study area entrance",
            countryCode = "LK"
        )
        db.savedPlaceDao().insertSavedPlace(place)

        val retrievedPlaces = db.savedPlaceDao().getAllSavedPlaces()
        assertTrue(retrievedPlaces.any { it.id == "test_home_1" })
        val retrieved = retrievedPlaces.first { it.id == "test_home_1" }
        assertEquals("Quiet study area entrance", retrieved.notes)
        assertEquals("LK", retrieved.countryCode)

        // Validate integrity check
        val report = com.example.data.local.DataPreservationManager.validateDatabaseIntegrity(context, db)
        assertTrue(report.isSuccessful)
        assertTrue(report.savedPlacesCount >= 1)
        assertEquals(2, report.databaseVersion)
    }

    @Test
    fun `removeDuplicateItems purges repeated saved places and travel history`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.repository.TravelWakeRepository(context)

        // Insert places with duplicate names
        val p1 = com.example.model.SavedPlace(
            id = "dup_1",
            name = "Colombo Fort Interchange",
            type = DestinationType.RAILWAY_STATION,
            latitude = 6.9344,
            longitude = 79.8500,
            address = "Platform 1",
            radiusMeters = 200f,
            defaultAlertMinutes = 7,
            preferredTransportMode = TransportMode.TRAIN
        )
        val p2 = com.example.model.SavedPlace(
            id = "dup_2",
            name = "Colombo Fort Interchange",
            type = DestinationType.RAILWAY_STATION,
            latitude = 6.9344,
            longitude = 79.8500,
            address = "Platform 2",
            radiusMeters = 200f,
            defaultAlertMinutes = 7,
            preferredTransportMode = TransportMode.TRAIN
        )

        repo.addSavedPlace(p1)
        repo.addSavedPlace(p2)

        val removed = repo.removeDuplicateItems()
        assertTrue("Duplicate items should be removed or prevented", removed >= 0)

        val currentPlaces = repo.savedPlaces.value
        val colomboFortCount = currentPlaces.count { it.name.equals("Colombo Fort Interchange", ignoreCase = true) }
        assertEquals("There should only be at most 1 item for Colombo Fort Interchange", 1, colomboFortCount)
    }

    @Test
    fun `searchPlaces removes duplicate search results and curated hubs`() = kotlinx.coroutines.runBlocking {
        val provider = com.example.data.provider.NominatimGeocodingProvider()
        val results = provider.searchPlaces(
            query = "Colombo",
            userLat = 6.9271,
            userLng = 79.8612,
            preferredCountryCode = "LK",
            searchWorldwide = false
        )

        // Ensure all returned place IDs and normalized names are distinct
        val distinctById = results.distinctBy { it.placeId }
        val distinctByNameAndCity = results.distinctBy { "${it.name.trim().lowercase()}_${it.city.trim().lowercase()}" }

        assertEquals(distinctById.size, results.size)
        assertEquals(distinctByNameAndCity.size, results.size)
    }

    @Test
    fun `country search returns unique country items`() {
        val results = com.example.model.CountryData.searchCountries("land")
        val uniqueCodes = results.distinctBy { it.code }
        assertEquals("All country results must be unique", uniqueCodes.size, results.size)
    }
}
