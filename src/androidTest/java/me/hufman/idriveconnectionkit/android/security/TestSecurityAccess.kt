package me.hufman.idriveconnectionkit.android.security

import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import junit.framework.Assert.*
import me.hufman.idriveconnectionkit.android.CarAPIClient
import me.hufman.idriveconnectionkit.android.CarAPIDiscovery
import me.hufman.idriveconnectionkit.android.CertMangling
import me.hufman.idriveconnectionkit.android.TestCarAPIDiscovery
import org.awaitility.Awaitility.await
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TestSecurityAccess {

	/**
	 * Only expected to succeed with Connected Classic
	 */
	@Test
	fun testCarChallengeResponse() {
		var callbackTriggered = false
		val appContext = InstrumentationRegistry.getTargetContext()
		val securityAccess = SecurityAccess(appContext, Runnable { callbackTriggered = true })

		// test that we can connect to a security service
		callbackTriggered = false
		securityAccess.connect()
		await().until { securityAccess.isConnected() }
		await("Waiting for pending connections").until { ! securityAccess.isConnecting() }
		assertTrue("Connected Classic connections", securityAccess.securityServiceManager.connectedSecurityServices.isNotEmpty())
		assertTrue("Active connections", securityAccess.securityServiceManager.securityConnections.isNotEmpty())

		assertEquals("Callback successfully triggered", true, callbackTriggered)

		// test that it signs a challenge for us
		val challenge = byteArrayOf(0x6d, 0x58, 0x5f, 0x14,
		                            0x72, 0x72, 0x19, 0x75,
		                            0x4e, 0x73, 0x19, 0x38,
		                            0x61, 0x2f, 0x50, 0x78)
		var response = securityAccess.signChallenge("TestSecurityServiceJava", challenge)
		assertEquals("Received a challenge response", 512, response.size)
		assertEquals("Received the correct challenge response", 0x15, response[0])

		// test that an invalid challenge is not accepted
		try {
			response = securityAccess.signChallenge("TestSecurityServiceJava", ByteArray(0))
			fail("Invalid challenge was signed, returned a response of size " + response.size)
		} catch (e: IllegalArgumentException) {
			assert(e.message!!.contains("Error while calling native function signChallenge"))
		}

		// test that the callback is triggered when disconnecting
		callbackTriggered = false
		var callbackTriggered2 = false
		securityAccess.listener = Runnable { callbackTriggered2 = true}
		securityAccess.disconnect()
		assertTrue("No active connections", securityAccess.securityServiceManager.securityConnections.isEmpty())
		assertFalse("Callback successfully not triggered", callbackTriggered)
		assertTrue("Callback successfully triggered", callbackTriggered2)
	}

	@Test
	fun testBMWCertificate() {
		val appContext = InstrumentationRegistry.getTargetContext()
		val securityAccess = SecurityAccess(appContext)

		securityAccess.connect()
		await().until { securityAccess.isConnected() }
		val bmwCert = securityAccess.fetchBMWCerts()
		val parsedCerts = CertMangling.loadCerts(bmwCert)
		assertNotNull(parsedCerts)
		val certNames = parsedCerts?.mapNotNull {
			CertMangling.getCN(it)
		} ?: LinkedList()
		assertEquals(2, certNames.size)
		assertTrue(certNames.containsAll(arrayListOf("a4a_app_Android_FeatureCertificate_PIA_KML_VOICE_00.00.01", "a4a_app_BMWTouchCommand_Connection_00.00.10")))
	}

	@Test
	fun testCertMangling() {
		val appContext = InstrumentationRegistry.getTargetContext()
		val securityAccess = SecurityAccess(appContext)

		// load up app cert
		val lock = Semaphore(0) // wait for the CarAPI to discover a specific app

		CarAPIDiscovery.discoverApps(appContext, TestCarAPIDiscovery.WaitForCarAPI("com.clearchannel.iheartradio.connect", lock))
		lock.tryAcquire(60000, TimeUnit.MILLISECONDS)    // wait up to 60s for the CarAPI app to respond
		CarAPIDiscovery.cancelDiscovery(appContext)
		assertTrue(CarAPIDiscovery.discoveredApps.containsKey("com.clearchannel.iheartradio.connect"))
		val app = CarAPIDiscovery.discoveredApps["com.clearchannel.iheartradio.connect"] as CarAPIClient
		val appCert = TestCarAPIDiscovery.loadInputStream(app.getAppCertificate() as InputStream)

		// load up bmw cert
		securityAccess.connect()
		await().until { securityAccess.isConnected() }
		val bmwCert = securityAccess.fetchBMWCerts()
		val combinedCert = CertMangling.mergeBMWCert(appCert, bmwCert)

		val parsedCerts = CertMangling.loadCerts(combinedCert)
		assertNotNull(parsedCerts)
		val certNames = parsedCerts?.mapNotNull {
			CertMangling.getCN(it)
		} ?: LinkedList()
		assertEquals(3, certNames.size)
		assertTrue(certNames.containsAll(arrayListOf("a4a_android-ca", "a4a_app_BMWTouchCommand_Connection_00.00.10", "a4a_app_iheartradioconnect___com_clearchannel_iheartradio_connect_00.00.18")))
	}
}