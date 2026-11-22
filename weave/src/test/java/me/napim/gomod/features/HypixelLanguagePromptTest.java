package me.napim.gomod.features;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HypixelLanguagePromptTest {

    @Before
    public void setUp() {
        HypixelLanguagePrompt.resetForTest();
    }

    @Test
    public void englishBlitzSidebarIsDetectedAsEnglish() {
        HypixelLanguagePrompt.LanguageState state = HypixelLanguagePrompt.classifySidebarLines(Arrays.asList(
                "\u00a7e\u00a7lBLITZ SG",
                "\u00a7711/22/26",
                "\u00a7fKills: \u00a7a12,345",
                "\u00a7fWins: \u00a7a1,234",
                "\u00a7fCoins: \u00a76500,000",
                "\u00a7ewww.hypixel.net"
        ));
        assertEquals(HypixelLanguagePrompt.LanguageState.ENGLISH, state);
    }

    @Test
    public void frenchSpanishGermanChineseSidebarsAreDetectedAsNonEnglish() {
        assertEquals(HypixelLanguagePrompt.LanguageState.NON_ENGLISH,
                HypixelLanguagePrompt.classifySidebarLines(Arrays.asList(
                        "\u00a7e\u00a7lBLITZ SG",
                        "\u00a7fPi\u00e8ces: \u00a7610,000",
                        "\u00a7fVictoires: \u00a7a120",
                        "\u00a7ewww.hypixel.net"
                )));
        assertEquals(HypixelLanguagePrompt.LanguageState.NON_ENGLISH,
                HypixelLanguagePrompt.classifySidebarLines(Arrays.asList(
                        "\u00a7e\u00a7lBLITZ SG",
                        "\u00a7fMonedas: \u00a7610,000",
                        "\u00a7fJugadores restantes: \u00a7a12",
                        "\u00a7ewww.hypixel.net"
                )));
        assertEquals(HypixelLanguagePrompt.LanguageState.NON_ENGLISH,
                HypixelLanguagePrompt.classifySidebarLines(Arrays.asList(
                        "\u00a7e\u00a7lBLITZ SG",
                        "\u00a7fM\u00fcnzen: \u00a7610,000",
                        "\u00a7fSiege: \u00a7a88",
                        "\u00a7ewww.hypixel.net"
                )));
        assertEquals(HypixelLanguagePrompt.LanguageState.NON_ENGLISH,
                HypixelLanguagePrompt.classifySidebarLines(Arrays.asList(
                        "\u00a7e\u00a7lBLITZ SG",
                        "\u00a7f\u786c\u5e01: \u00a7610,000",
                        "\u00a7f\u80dc\u5229: \u00a7a88",
                        "\u00a7ewww.hypixel.net"
                )));
    }

    @Test
    public void unknownSidebarDoesNotFalsePositive() {
        assertEquals(HypixelLanguagePrompt.LanguageState.UNKNOWN,
                HypixelLanguagePrompt.classifySidebarLines(Arrays.asList(
                        "\u00a7e\u00a7lHYPIXEL",
                        "\u00a7711/22/26",
                        "\u00a7ewww.hypixel.net"
                )));
        assertEquals(HypixelLanguagePrompt.LanguageState.UNKNOWN,
                HypixelLanguagePrompt.classifySidebarLines(Collections.<String>emptyList()));
    }

    @Test
    public void hypixelContextRequiresHypixelDomain() {
        assertTrue(HypixelLanguagePrompt.isHypixelContext("mc.hypixel.net", Collections.<String>emptyList()));
        assertTrue(HypixelLanguagePrompt.isHypixelContext(null, Arrays.asList("\u00a7ewww.hypixel.net")));
        assertFalse(HypixelLanguagePrompt.isHypixelContext("na.minemen.club", Arrays.asList("\u00a7dminemen.club")));
    }
}
