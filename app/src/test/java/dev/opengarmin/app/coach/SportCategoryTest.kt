package dev.opengarmin.app.coach

import org.junit.Assert.assertEquals
import org.junit.Test

class SportCategoryTest {
    @Test
    fun `Garmin cardio training is its own category`() {
        assertEquals(SportCategory.Cardio, categoryForSport(10, 26))
    }

    @Test
    fun `training sub-sports keep their real category`() {
        assertEquals(SportCategory.Strength, categoryForSport(10, 20))
        assertEquals(SportCategory.Other, categoryForSport(10, 0))
        assertEquals(SportCategory.Other, categoryForSport(10, 43))
    }

    @Test
    fun `run ride walk and strength map as before`() {
        assertEquals(SportCategory.Run, categoryForSport(1, 0))
        assertEquals(SportCategory.Ride, categoryForSport(2, 8))
        assertEquals(SportCategory.Walk, categoryForSport(11, 0))
        assertEquals(SportCategory.Strength, categoryForSport(4, 0))
        assertEquals(SportCategory.Strength, categoryForSport(62, 0))
    }

    @Test
    fun `unknown sports remain available under Other`() {
        assertEquals(SportCategory.Other, categoryForSport(5, 17))
        assertEquals(SportCategory.Walk, categoryForSport(17, 0))
    }
}
