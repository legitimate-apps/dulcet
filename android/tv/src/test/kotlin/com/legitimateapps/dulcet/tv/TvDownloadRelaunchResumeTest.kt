package com.legitimateapps.dulcet.tv

import android.app.Application
import com.legitimateapps.dulcet.search.conformance.DownloadRelaunchResumeScenario
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV app's downloads survive a dropped connection and a relaunch without starting over (spec
 * §14.5). [DownloadRelaunchResumeScenario] drives the production registry, WorkManager task and
 * worker in the TV app's own runtime; only the server and the Keystore are stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"])
class TvDownloadRelaunchResumeTest {
    private val scenario = DownloadRelaunchResumeScenario()

    @Before fun setUp() = scenario.setUp()
    @After fun tearDown() = scenario.tearDown()

    @Test fun aPartialFileKeptAfterADroppedConnectionIsResumedWithARangeRequestAfterARelaunch() = scenario.run()
}
