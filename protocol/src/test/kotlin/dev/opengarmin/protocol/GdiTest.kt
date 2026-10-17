package dev.opengarmin.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GdiTest {
    @Test
    fun `frame round-trips`() {
        val frame = GdiFrame.build(id = 0x08bc, offset = 0, totalLength = 5, chunk = Hex.decode("aa01023a00"))
        val parsed = assertNotNull(GdiFrame.parse(frame))
        assertEquals(0x08bc, parsed.id)
        assertEquals(0, parsed.offset)
        assertEquals(5, parsed.totalLength)
        assertTrue(parsed.payload.contentEquals(Hex.decode("aa01023a00")))
    }

    @Test
    fun `chunks and reassembles a large payload`() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        val chunks = GdiFrame.chunks(id = 7, payload = payload, chunkSize = 487)
        assertEquals(3, chunks.size)

        val buf = ByteArray(payload.size)
        var received = 0
        for (c in chunks) {
            val f = assertNotNull(GdiFrame.parse(c))
            assertEquals(payload.size, f.totalLength)
            System.arraycopy(f.payload, 0, buf, f.offset, f.payload.size)
            received += f.payload.size
        }
        assertEquals(payload.size, received)
        assertTrue(buf.contentEquals(payload))
    }

    @Test
    fun `item list request has the expected wire bytes`() {
        val req = Gdi.itemListRequest(maxItemCount = 100)
        val smart = Gdi.wrapSmart(Gdi.SMART_FILE_ACCESS, req)
        assertEquals("da02044a021864", Hex.encode(smart))
    }

    @Test
    fun `builds responses for device-initiated requests`() {
        // Core.LocationUpdatedSetEnabledRequest { enabled=true, requests=[{requested=0}] }
        assertEquals(
            "6a0a32080801120408001001",
            Hex.encode(Gdi.locationUpdatedSetEnabledResponse(Hex.decode("080112020800"))),
        )
        // SmsCannedListRequest { requested_types=[4,1] }
        assertEquals(
            "82010a2a081202080412020801",
            Hex.encode(Gdi.smsCannedListResponse(Hex.decode("0a020401"))),
        )
        // DeviceMessages.CIQMessageRequest { event_id="abc" }
        assertEquals(
            "c2010922070a036162631001",
            Hex.encode(Gdi.ciqMessageResponse(Hex.decode("0a03616263"))),
        )
        // AudioPrompts.SetLanguageRequest -> success
        assertEquals(
            "52046a020801",
            Hex.encode(Gdi.audioSetLanguageResponse()),
        )
        // ConnectIQHTTPRequest -> offline response
        assertEquals(
            "1205120308ac02",
            Hex.encode(Gdi.connectIqHttpResponse()),
        )
        // Core.GetLocationRequest -> NO_VALID_LOCATION
        assertEquals(
            "6a0422020802",
            Hex.encode(Gdi.getLocationResponse()),
        )
    }

    @Test
    fun `parses an item list response`() {
        val uid = Gdi.Uuid(1L, 2L)
        val dt = Gdi.DataTypeInfo(2, null, 0, null, null, 0, null)
        val item = ProtoWriter()
            .bytes(1, Gdi.encodeUuid(uid))
            .bytes(2, Gdi.encodeDataType(dt))
            .uint64(3, 1234L)
            .uint32(4, 0x10)
            .uint32(5, 7)
            .uint32(6, 1)
            .toByteArray()
        val list = ProtoWriter()
            .uint32(2, 5)
            .uint32(3, 9)
            .bytes(4, item)
            .toByteArray()
        val service = ProtoWriter().bytes(10, list).toByteArray()

        val parsed = assertNotNull(Gdi.parseFileAccessService(service).itemListResponse)
        assertEquals(0, parsed.status)
        assertEquals(5, parsed.sessionId)
        assertEquals(9, parsed.nextTransactionId)
        assertEquals(1, parsed.items.size)
        val first = parsed.items.first()
        assertEquals("00000000000000010000000000000002", first.uid?.hex())
        assertEquals(2, first.dataType?.format)
        assertEquals(1234L, first.dataSize)
        assertEquals(0x10, first.requestedFlags)
        assertEquals(7, first.transactionId)
    }

    @Test
    fun `pull item request encodes the full item reference`() {
        val item = Gdi.ItemReference(
            uid = Gdi.Uuid(1L, 2L),
            dataType = null,
            dataSize = 110,
            requestedFlags = 0,
            transactionId = 5,
            urgency = 0,
        )
        val req = Gdi.pullItemRequest(item, priority = 30, offset = 0, compressionWindow = 15)
        assertEquals(
            "0a200a180a12090100000000000000110200000000000000186e2805101e1800280f",
            Hex.encode(req),
        )
    }

    @Test
    fun `parses a pull response`() {
        val pr = ProtoWriter()
            .uint32(3, 640)
            .uint32(7, 11)
            .toByteArray()
        val service = ProtoWriter().bytes(2, pr).toByteArray()
        val parsed = assertNotNull(Gdi.parseFileAccessService(service).pullItemResponse)
        assertEquals(0, parsed.result)
        assertEquals(0, parsed.transport)
        assertEquals(640, parsed.transferHandle)
        assertEquals(11, parsed.compressionWindow)
    }

    @Test
    fun `parses an item added notification`() {
        val smart = Hex.decode(
            "da023462320a300a1209fd000000fc5e3645116b0300000000000112110800120b" +
                "4649545f545950455f3332180018eb0628cedf023001",
        )
        val service = assertNotNull(Gdi.smartFields(smart)[Gdi.SMART_FILE_ACCESS])
        val added = assertNotNull(Gdi.parseFileAccessService(service).itemAdded)
        assertEquals(1, added.items.size)
        val item = added.items.first()
        assertEquals("FIT_TYPE_32", item.dataType?.gdxmlString)
        assertEquals(875L, item.dataSize)
        assertEquals(1, item.urgency)
    }

    @Test
    fun `builds cut wellness request and parses its response`() {
        assertEquals(
            "fa02041a020801",
            Hex.encode(Gdi.cutWellnessFilesRequest()),
        )
        val response = Hex.decode("fa020422020800")
        assertEquals(0, Gdi.cutWellnessFilesStatus(response))
        val notification = Hex.decode("fa02022a00")
        assertTrue(Gdi.isWellnessThresholdMet(notification))
    }

    @Test
    fun `feature capabilities advertise garmin connect wellness`() {
        val smart = Gdi.featureCapabilitiesRequest()
        val core = assertNotNull(Gdi.smartFields(smart)[Gdi.SMART_CORE])
        val request = assertNotNull(Gdi.field(core, 8))
        assertNotNull(Gdi.field(request, 11))
        assertNotNull(Gdi.field(request, 16))
    }
}
