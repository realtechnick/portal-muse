package dev.wobsoriano.museportal.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceOptionsTest {
  @Test
  fun thePortalsNumberedVoicesGetReadableNamesWithTheDefaultRegionFirst() {
    val names = listOf("de_DE.0", "en_GB.10", "en_GB.7", "en_US.44", "en_US.0", "en_US.5", "fr_FR.0")
    assertEquals(
        listOf(
            VoiceOption("en_US.0", "English (United States) · Voice 1"),
            VoiceOption("en_US.5", "English (United States) · Voice 2"),
            VoiceOption("en_US.44", "English (United States) · Voice 3"),
            VoiceOption("en_GB.7", "English (United Kingdom) · Voice 1"),
            VoiceOption("en_GB.10", "English (United Kingdom) · Voice 2"),
            VoiceOption("fr_FR.0", "French (France)"),
            VoiceOption("de_DE.0", "German (Germany)"),
        ),
        voiceOptions(names, default = "en_US.0"))
  }
}
