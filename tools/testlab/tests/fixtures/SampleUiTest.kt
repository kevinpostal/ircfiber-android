package com.example.app

import org.junit.Test

/** The class under test is the whole app; a class word in a comment is not a declaration. */
@RunWith(AndroidJUnit4::class)
class SampleUiTest {
    @get:Rule
    val rule = createRule()

    @Test
    fun plain() {
        rule.launch()
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun annotated() {
        rule.launch()
    }

    @Test @FlakyTest fun sameLine() = runBlocking {
        rule.launch()
    }

    fun notATest() {}
}
